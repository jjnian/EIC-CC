package com.tuiyan.backend.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * SSE 后台作业模板：把「建 emitter → 丢后台线程 → 透传工作空间上下文 → 回 step 进度 →
 * complete 结果 / error 兜底」这套<b>横切样板</b>收敛到一处,控制器只写业务 lambda。
 * <p>此前该样板在 6 个控制器里各手抄一份,工作空间透传、错误脱敏、超时、线程池、池满拒绝这些
 * 横切关注点散落——改一处 SSE 契约要动多个文件、且易漏(如忘了错误脱敏就泄露内部异常)。集中后:
 * <ul>
 *   <li><b>工作空间透传</b>:提交线程的 {@code WorkspaceContext} 捕获后在 worker 线程 set,结束 clear;</li>
 *   <li><b>错误脱敏</b>:未预期异常不外泄(与 {@code GlobalExceptionHandler} 同策略),仅记服务端日志;</li>
 *   <li><b>池满拒绝</b>:{@link TaskRejectedException} 转成友好的 SSE error,而非 500。</li>
 * </ul>
 */
@Component
public class SseJobRunner {

    private static final Logger log = LoggerFactory.getLogger(SseJobRunner.class);

    /** 业务任务:拿到 step 进度回调,产出一个可序列化为 complete 事件的结果。 */
    @FunctionalInterface
    public interface SseTask {
        Object run(StepSink step) throws Exception;
    }

    /** 进度回调:key=阶段标识,label=展示文案 → SSE {@code step} 事件。 */
    @FunctionalInterface
    public interface StepSink {
        void emit(String key, String label);
    }

    private final AsyncTaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;

    public SseJobRunner(@Qualifier("appTaskExecutor") AsyncTaskExecutor taskExecutor, ObjectMapper objectMapper) {
        this.taskExecutor = taskExecutor;
        this.objectMapper = objectMapper;
    }

    /** 默认超时文案的重载。 */
    public SseEmitter run(long timeoutMs, String fallbackError, SseTask task) {
        return run(timeoutMs, "任务超时（>" + (timeoutMs / 60000) + "min），请稍后重试", fallbackError, task);
    }

    /**
     * 跑一个 SSE 后台作业。
     * @param timeoutMs     SSE 超时(毫秒)
     * @param timeoutMsg    超时提示文案
     * @param fallbackError 未预期异常时对客的通用文案(受控业务异常仍原样回传其 message)
     * @param task          业务逻辑;返回值序列化为 complete 事件
     */
    public SseEmitter run(long timeoutMs, String timeoutMsg, String fallbackError, SseTask task) {
        final String workspaceId = WorkspaceContext.get();
        SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(timeoutMs, timeoutMsg);
        SseEmitter emitter = ce.emitter();
        Runnable job = () -> {
            if (workspaceId != null) WorkspaceContext.set(workspaceId);
            try {
                StepSink step = (key, label) -> {
                    try {
                        String json = objectMapper.writeValueAsString(Map.of("key", key, "label", label));
                        SsePushUtils.safeSend(emitter, ce.cancelled(), "step", json);
                    } catch (Exception ignore) { /* step 序列化失败不影响主流程 */ }
                };
                Object result = task.run(step);
                SsePushUtils.safeSend(emitter, ce.cancelled(), "complete", objectMapper.writeValueAsString(result));
                emitter.complete();
            } catch (Exception e) {
                SsePushUtils.safeSend(emitter, ce.cancelled(), "error", SsePushUtils.clientSafeError(e, fallbackError));
                emitter.complete();
            } finally {
                WorkspaceContext.clear();
            }
        };
        try {
            taskExecutor.execute(job);
        } catch (TaskRejectedException rej) {
            log.warn("[sse] 任务被拒(线程池已满): {}", rej.getMessage());
            SsePushUtils.safeSend(emitter, ce.cancelled(), "error", "服务繁忙，正在进行的任务过多，请稍后重试");
            emitter.complete();
        }
        return emitter;
    }
}

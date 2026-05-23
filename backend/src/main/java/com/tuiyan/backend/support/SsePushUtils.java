package com.tuiyan.backend.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SseEmitter 装配与安全发送工具。
 * <p>提供统一的超时 / 异常处理 + cancelled 标志的 safeSend，
 * 让 controller / service 不必各自重复处理 SSE 边界情况。
 */
public final class SsePushUtils {

    private static final Logger log = LoggerFactory.getLogger(SsePushUtils.class);

    private SsePushUtils() {}

    /**
     * emitter + 与之绑定的 cancelled 标志（同时设置 onTimeout/onCompletion/onError）。
     * <p>用 record 是因为这两个对象只是"打包传递"的关系，没有任何行为，
     * 直接暴露字段比再写一对 getter 简洁。
     */
    public record CancellableEmitter(SseEmitter emitter, AtomicBoolean cancelled) {}

    /**
     * 创建一个带超时兜底 + 取消标志的 emitter。
     * <p>关键设计：在 controller 同步线程内一次性装好所有回调，
     * 避免与异步业务线程之间的注册时序窗口（不然 emitter 可能在业务线程尚未注册回调时就已超时）。
     *
     * <p>触发场景：
     * <ul>
     *   <li>timeoutMs 超时 → 先置 cancelled，再发 error 事件，最后 complete；</li>
     *   <li>客户端断开 / emitter 关闭 → cancelled 置位，业务循环里轮询到此值后提前 return；</li>
     *   <li>emitter 自身 onError → 同上。</li>
     * </ul>
     */
    public static CancellableEmitter newCancellableEmitter(long timeoutMs, String timeoutMessage) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onTimeout(() -> {
            cancelled.set(true);
            try {
                // 在 complete 前再发一帧 error，让前端能展示具体超时原因
                emitter.send(SseEmitter.event().name("error").data(timeoutMessage));
            } catch (IOException e) {
                log.warn("emit timeout-message failed: {}", e.toString());
            }
            emitter.complete();
        });
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onError(t -> {
            cancelled.set(true);
            log.warn("emitter error: {}", t.toString());
        });
        return new CancellableEmitter(emitter, cancelled);
    }

    /** 默认超时文案版本：自动把毫秒换算成秒拼到中文提示里。 */
    public static CancellableEmitter newCancellableEmitter(long timeoutMs) {
        return newCancellableEmitter(timeoutMs,
                "LLM 响应超时(>" + (timeoutMs / 1000) + "s),请检查 LLM 配置或网络后重试");
    }

    /**
     * 安全发送一帧 SSE 事件。
     * <p>cancelled 已置位时直接跳过（不再触发 IO）；
     * send 抛错时记录日志并把 cancelled 置位、返回 false，告诉调用方该退出循环了。
     *
     * @return true=成功发出，false=已取消或失败（调用方应停止后续推送）
     */
    public static boolean safeSend(SseEmitter emitter, AtomicBoolean cancelled, String event, String data) {
        if (cancelled.get()) return false;
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IllegalStateException stateErr) {
            // emitter 已被 complete，再 send 会抛此异常
            log.warn("emitter already closed when sending {}: {}", event, stateErr.toString());
            cancelled.set(true);
            return false;
        } catch (IOException ioErr) {
            // 客户端断开 / 网络异常
            log.warn("emit {} failed (client disconnected?): {}", event, ioErr.toString());
            cancelled.set(true);
            return false;
        }
    }
}

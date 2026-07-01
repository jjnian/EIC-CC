package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.repository.ExperienceRepository;
import com.tuiyan.backend.service.agent.ExplorationAgentService;
import com.tuiyan.backend.support.SsePushUtils;
import com.tuiyan.backend.support.WebUrls;
import com.tuiyan.backend.support.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自动探索端点:像人一样"用"一个 web 系统去摸清功能、反推业务,结果落成一篇经验。
 * <p>SSE 流:step(每一步:打开/读页/理解/点击/拦截) → complete(携带新建经验) → 结束;失败发 error。
 */
@RestController
@RequestMapping("/api/explore")
public class ExploreController {

    private static final Logger log = LoggerFactory.getLogger(ExploreController.class);
    private final ExplorationAgentService agent;
    private final ExperienceRepository experienceRepo;
    private final AsyncTaskExecutor taskExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExploreController(ExplorationAgentService agent,
                             ExperienceRepository experienceRepo,
                             @Qualifier("predictionExecutor") AsyncTaskExecutor taskExecutor) {
        this.agent = agent;
        this.experienceRepo = experienceRepo;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 启动一次探索。请求体:
     * { baseUrl, username?/password?(填了则探索前自动登录系统), maxSteps?,
     *   readOnly?(默认 true), storageState?(预登录 cookies JSON), modelOverride?, configId? }
     */
    @PostMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@RequestBody(required = false) Map<String, Object> body) {
        return runExploration(
                str(body, "baseUrl"), str(body, "storageState"),
                str(body, "username"), str(body, "password"),
                intOr(body, "maxSteps", 30),
                !"false".equalsIgnoreCase(str(body, "readOnly")), // 默认只读
                str(body, "modelOverride"), str(body, "configId"), null);
    }

    /**
     * 探索一个已接入并保存的 web 系统：按其保存的连接配置（含真实密码，服务端读取，不经前端）运行探索，
     * 每次另产一篇 origin=explore 的业务说明经验。请求体：{ experienceId, modelOverride?, configId? }。
     */
    @PostMapping(value = "/run-saved", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runSaved(@RequestBody(required = false) Map<String, Object> body) {
        String experienceId = str(body, "experienceId");
        Map<String, Object> cfg = experienceId == null ? null : experienceRepo.readSourceConfigScoped(experienceId);
        if (cfg == null) {
            // 走与运行期一致的 SSE 错误通道，前端统一在 onError 处理
            SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(5_000L, "");
            SseEmitter emitter = ce.emitter();
            SsePushUtils.safeSend(emitter, ce.cancelled(), "error", "找不到该 web 系统或无权访问");
            emitter.complete();
            return emitter;
        }
        return runExploration(
                str(cfg, "baseUrl"), str(cfg, "storageState"),
                str(cfg, "username"), str(cfg, "password"),
                intOr(cfg, "maxSteps", 30),
                !Boolean.FALSE.equals(cfg.get("readOnly")), // 默认只读
                str(body, "modelOverride"), str(body, "configId"), experienceId);
    }

    /**
     * 探索 SSE 主流程（/run 与 /run-saved 共用）：异步跑 agent.explore，step 流式推、complete 携带新建经验。
     * @param sourceExperienceId 已保存 web 系统的经验 id（仅 /run-saved 传入）；非空时,登录成功后把
     *                           storageState 回存到该条目的连接配置,供下次免登录(走服务端通道,不经前端)。
     */
    private SseEmitter runExploration(String baseUrl, String storageState, String username, String password,
                                      int maxSteps, boolean readOnly, String modelOverride, String configId,
                                      String sourceExperienceId) {
        String workspaceId = WorkspaceContext.get();
        SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(600_000L,
                "系统探索超时 (>600s)，请缩小探索步数或稍后重试");
        SseEmitter emitter = ce.emitter();

        taskExecutor.execute(() -> {
            if (workspaceId != null) WorkspaceContext.set(workspaceId);
            try {
                // 规范化并校验入口地址：补全 https://、拦掉 asdasd 这类非网址，
                // 避免把 Playwright 的「Cannot navigate to invalid URL」长栈直接抛给用户
                String entryUrl = WebUrls.normalizeEntryUrl(baseUrl);
                ExplorationAgentService.StepSink step = (key, label) -> {
                    try {
                        String json = objectMapper.writeValueAsString(Map.of("key", key, "label", label));
                        SsePushUtils.safeSend(emitter, ce.cancelled(), "step", json);
                    } catch (Exception ignore) {}
                };
                // 登录成功后回存 storageState（仅已保存的 web 系统;敏感,不经前端）
                java.util.function.Consumer<String> onStorageState = sourceExperienceId == null ? null : ss -> {
                    try { experienceRepo.patchSourceConfig(sourceExperienceId, Map.of("storageState", ss)); }
                    catch (RuntimeException e) { log.warn("[explore] 回存 storageState 失败: {}", e.toString()); }
                };
                Map<String, Object> exp = agent.explore(entryUrl, storageState, username, password,
                        maxSteps, readOnly, modelOverride, configId, step, ce.cancelled()::get, onStorageState);
                Map<String, Object> payload = new LinkedHashMap<>(exp);
                SsePushUtils.safeSend(emitter, ce.cancelled(), "complete",
                        objectMapper.writeValueAsString(payload));
                emitter.complete();
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                SsePushUtils.safeSend(emitter, ce.cancelled(), "error", msg);
                emitter.complete();
            } finally {
                WorkspaceContext.clear();
            }
        });
        return emitter;
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intOr(Map<String, Object> body, String key, int dflt) {
        String s = str(body, key);
        if (s == null || s.isBlank()) return dflt;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return dflt; }
    }
}

package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.service.agent.ExplorationAgentService;
import com.tuiyan.backend.support.SsePushUtils;
import com.tuiyan.backend.support.WorkspaceContext;
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

    private final ExplorationAgentService agent;
    private final AsyncTaskExecutor taskExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExploreController(ExplorationAgentService agent,
                             @Qualifier("predictionExecutor") AsyncTaskExecutor taskExecutor) {
        this.agent = agent;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 启动一次探索。请求体:
     * { baseUrl, username?/password?(填了则探索前自动登录系统), maxSteps?,
     *   readOnly?(默认 true), storageState?(预登录 cookies JSON), modelOverride?, configId? }
     */
    @PostMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@RequestBody(required = false) Map<String, Object> body) {
        String baseUrl = str(body, "baseUrl");
        String storageState = str(body, "storageState");
        String username = str(body, "username");
        String password = str(body, "password");
        int maxSteps = intOr(body, "maxSteps", 15);
        boolean readOnly = !"false".equalsIgnoreCase(str(body, "readOnly")); // 默认只读
        String modelOverride = str(body, "modelOverride");
        String configId = str(body, "configId");
        String workspaceId = WorkspaceContext.get();

        SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(600_000L,
                "系统探索超时 (>600s)，请缩小探索步数或稍后重试");
        SseEmitter emitter = ce.emitter();

        taskExecutor.execute(() -> {
            if (workspaceId != null) WorkspaceContext.set(workspaceId);
            try {
                if (baseUrl == null || baseUrl.isBlank()) {
                    throw new IllegalArgumentException("缺少 baseUrl(系统入口地址)");
                }
                ExplorationAgentService.StepSink step = (key, label) -> {
                    try {
                        String json = objectMapper.writeValueAsString(Map.of("key", key, "label", label));
                        SsePushUtils.safeSend(emitter, ce.cancelled(), "step", json);
                    } catch (Exception ignore) {}
                };
                Map<String, Object> exp = agent.explore(baseUrl.trim(), storageState, username, password,
                        maxSteps, readOnly, modelOverride, configId, step);
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

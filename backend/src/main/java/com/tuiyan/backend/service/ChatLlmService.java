package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.model.ChatRequest;
import com.tuiyan.backend.service.llm.GraphPromptBuilder;
import com.tuiyan.backend.service.llm.LlmCallLogger;
import com.tuiyan.backend.service.llm.LlmHttpClient;
import com.tuiyan.backend.service.llm.LlmPrompts;
import com.tuiyan.backend.service.llm.LlmStreamParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 聊天业务 facade：把"用户消息 + 图谱上下文 + 历史 + 附件"翻译成 LLM 调用，
 * 同步返回 JSON 或通过 SSE 流式推送。
 */
@Service
public class ChatLlmService {

    private static final Logger log = LoggerFactory.getLogger(ChatLlmService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmHttpClient http;
    private final LlmStreamParser streamParser;
    private final GraphPromptBuilder promptBuilder;
    private final LlmCallLogger callLogger;

    public ChatLlmService(LlmHttpClient http,
                          LlmStreamParser streamParser,
                          GraphPromptBuilder promptBuilder,
                          LlmCallLogger callLogger) {
        this.http = http;
        this.streamParser = streamParser;
        this.promptBuilder = promptBuilder;
        this.callLogger = callLogger;
    }

    /**
     * 同步 chat：把图谱上下文 + 用户消息发给 LLM，返回 {reply, add_nodes, add_edges} 形状的 JSON。
     */
    public JsonNode chat(List<Map<String, Object>> nodes, List<Map<String, Object>> edges, String message,
                         String modelOverride, String configId, List<Map<String, Object>> history,
                         List<Map<String, Object>> attachments) throws IOException {
        LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(modelOverride, configId);
        boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        log.info("[LLM-chat] 开始请求 model={} url={} protocol={}", cfg.modelName(), cfg.baseURL(),
                anthropic ? "anthropic" : "openai");

        String prompt = promptBuilder.buildChatPrompt(nodes, edges, message);
        callLogger.logConversation("LLM-chat", cfg.modelName(), LlmPrompts.CHAT_SYSTEM, history, prompt, attachments);

        String requestBody = http.buildBody(cfg, LlmPrompts.CHAT_SYSTEM, prompt, history, attachments, false, true);
        log.debug("[LLM-chat] 请求体大小: {} chars", requestBody.length());

        long startTime = System.currentTimeMillis();
        try {
            HttpRequest request = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody, cfg.rawUrl());
            HttpResponse<String> response = http.sendHttp(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            if (response.statusCode() != 200) {
                log.error("[LLM-chat] 请求失败 status={} 耗时={}ms", response.statusCode(), elapsed);
                callLogger.logUpstreamError("chat", response.statusCode(), response.body());
                http.metrics().recordCall(cfg.modelName(), elapsed, false);
                throw new RuntimeException("LLM 调用失败 HTTP " + response.statusCode() + "（详情见服务器日志）");
            }

            log.info("[LLM-chat] 请求成功 status=200 耗时={}ms 响应大小={} chars", elapsed, response.body().length());

            String responseContentType = response.headers().firstValue("Content-Type").orElse("");
            if (responseContentType.contains("text/html")) {
                log.error("[LLM-chat] 收到 HTML 响应而非 JSON，base-url 可能缺少 /v1 路径前缀");
                http.metrics().recordCall(cfg.modelName(), elapsed, false);
                throw new RuntimeException("LLM 代理返回了 HTML 页面而非 API 响应，请检查 base-url 配置是否包含 /v1 路径");
            }

            http.metrics().recordCall(cfg.modelName(), elapsed, true);

            JsonNode responseJson = objectMapper.readTree(response.body());
            String content = http.stripJsonFence(http.extractContent(responseJson, anthropic));
            callLogger.logLlmResponse("LLM-chat", cfg.modelName(), elapsed, content);
            return objectMapper.readTree(content);
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            http.metrics().recordCall(cfg.modelName(), elapsed, false);
            throw e;
        }
    }

    /**
     * 流式 chat：HTTP 异步 + SSE 转发，把 LLM 的增量 text 通过 emitter 实时推回前端。
     * <p>累积完整 content 后再解 JSON：LLM 的 JSON 可能在最后才闭合 {}，半流式解析意义不大反而出错率高。
     */
    public void chatStreaming(ChatRequest request, SseEmitter emitter) {
        long streamStartMs = System.currentTimeMillis();
        String streamModelName = "unknown";
        try {
            LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(request.getModelOverride(), request.getConfigId());
            streamModelName = cfg.modelName();
            boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

            log.info("[LLM-stream] 开始流式请求 model={} url={} protocol={}", cfg.modelName(), cfg.baseURL(),
                    anthropic ? "anthropic" : "openai");

            String prompt = promptBuilder.buildChatPrompt(request.getNodes(), request.getEdges(), request.getMessage());
            callLogger.logConversation("LLM-stream", cfg.modelName(), LlmPrompts.CHAT_SYSTEM,
                    request.getHistory(), prompt, request.getAttachments());

            String requestBody = http.buildBody(cfg, LlmPrompts.CHAT_SYSTEM, prompt,
                    request.getHistory(), request.getAttachments(), true, true);
            log.debug("[LLM-stream] 请求体大小: {} chars", requestBody.length());

            HttpRequest httpRequest = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody, cfg.rawUrl());

            final String modelNameForMetrics = cfg.modelName();

            http.httpClient().sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(resp -> handleStreamResponse(resp, emitter, anthropic, streamStartMs, modelNameForMetrics))
                    .exceptionally(ex -> {
                        long totalTime = System.currentTimeMillis() - streamStartMs;
                        log.error("[LLM-stream] 网络异常 耗时={}ms error={}", totalTime, ex.getMessage());
                        http.metrics().recordCall(modelNameForMetrics, totalTime, false);
                        try {
                            emitter.send(SseEmitter.event().name("error").data("Network error: " + ex.getMessage()));
                        } catch (IOException e) {
                            log.warn("emit network-error failed", e);
                        }
                        emitter.completeWithError(ex);
                        return null;
                    });
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - streamStartMs;
            log.error("[LLM-stream] 初始化失败: {}", e.getMessage());
            http.metrics().recordCall(streamModelName, elapsed, false);
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ioEx) {
                log.warn("emit init-error failed", ioEx);
            }
            emitter.completeWithError(e);
        }
    }

    private void handleStreamResponse(HttpResponse<java.io.InputStream> resp, SseEmitter emitter,
                                      boolean anthropic, long streamStartMs, String modelName) {
        long firstByteTime = System.currentTimeMillis() - streamStartMs;
        if (resp.statusCode() != 200) {
            String errorBody;
            try {
                errorBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                errorBody = "Unknown error";
            }
            log.error("[LLM-stream] 请求失败 status={} 首字节耗时={}ms", resp.statusCode(), firstByteTime);
            callLogger.logUpstreamError("chatStreaming", resp.statusCode(), errorBody);
            http.metrics().recordCall(modelName, firstByteTime, false);
            try {
                emitter.send(SseEmitter.event().name("error").data(
                        "LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）"));
            } catch (IOException e) {
                log.warn("emit error event failed", e);
            }
            emitter.complete();
            return;
        }

        log.info("[LLM-stream] 连接成功 首字节耗时={}ms", firstByteTime);

        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains("text/html")) {
            log.error("[LLM-stream] 收到 HTML 响应而非 SSE 流，base-url 可能缺少 /v1 路径前缀 Content-Type={}", contentType);
            http.metrics().recordCall(modelName, firstByteTime, false);
            try {
                emitter.send(SseEmitter.event().name("error").data(
                        "LLM 代理返回了 HTML 页面而非 API 响应，请检查 base-url 配置是否包含 /v1 路径"));
            } catch (IOException e) {
                log.warn("emit html-error event failed", e);
            }
            emitter.complete();
            return;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            StringBuilder fullContent = streamParser.parse(reader, emitter, anthropic);

            long totalTime = System.currentTimeMillis() - streamStartMs;
            log.info("[LLM-stream] 流式完成 总耗时={}ms 响应长度={} chars", totalTime, fullContent.length());
            http.metrics().recordCall(modelName, totalTime, true);

            String content = http.stripJsonFence(fullContent.toString());
            callLogger.logLlmResponse("LLM-stream", modelName, totalTime, content);
            JsonNode result = objectMapper.readTree(content);

            ObjectNode finalEvent = objectMapper.createObjectNode();
            finalEvent.put("reply", result.path("reply").asText(""));
            finalEvent.set("add_nodes", result.path("add_nodes"));
            finalEvent.set("add_edges", result.path("add_edges"));
            try {
                emitter.send(SseEmitter.event().name("complete")
                        .data(objectMapper.writeValueAsString(finalEvent)));
            } catch (IOException sendErr) {
                log.warn("emit complete failed (client likely disconnected): {}", sendErr.toString());
            }
            emitter.complete();
        } catch (IOException e) {
            long totalTime = System.currentTimeMillis() - streamStartMs;
            log.error("[LLM-stream] 响应解析失败: {}", e.getMessage());
            http.metrics().recordCall(modelName, totalTime, false);
            try {
                emitter.send(SseEmitter.event().name("error").data("Failed to parse response: " + e.getMessage()));
            } catch (IOException ex) {
                log.warn("emit parse-error failed", ex);
            }
            emitter.complete();
        }
    }
}

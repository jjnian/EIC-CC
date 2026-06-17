package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.service.llm.LlmCallLogger;
import com.tuiyan.backend.service.llm.LlmHttpClient;
import com.tuiyan.backend.service.llm.prompt.ExplainPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 推演节点三段式解释业务 facade（P1-7）。
 * <p>由 {@link ScenarioExplanationService} 构造好 userPrompt（含因果链上下文）后调用本类，
 * 本类只做协议适配 + JSON 解析，返回 evidence / assumptions / counterexamples 三字段的原始 JsonNode。
 */
@Service
public class ExplainLlmService {

    private static final Logger log = LoggerFactory.getLogger(ExplainLlmService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmHttpClient http;
    private final LlmCallLogger callLogger;

    public ExplainLlmService(LlmHttpClient http, LlmCallLogger callLogger) {
        this.http = http;
        this.callLogger = callLogger;
    }

    public record ExplainResult(JsonNode json, String modelName) {}

    public ExplainResult explainNode(String userPrompt, String modelOverride, String configId) throws IOException {
        LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(modelOverride, configId);
        boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        log.info("[LLM-explain] 开始 explain model={} url={}", cfg.modelName(), cfg.baseURL());
        callLogger.logConversation("LLM-explain", cfg.modelName(), ExplainPrompts.EXPLAIN_SYSTEM, null, userPrompt, null);

        String requestBody = http.buildBody(cfg, ExplainPrompts.EXPLAIN_SYSTEM, userPrompt, null, null, false, true);

        long startTime = System.currentTimeMillis();
        try {
            HttpRequest httpReq = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody, cfg.rawUrl());
            HttpResponse<String> resp = http.sendHttp(httpReq, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            if (resp.statusCode() != 200) {
                log.error("[LLM-explain] 请求失败 status={} 耗时={}ms", resp.statusCode(), elapsed);
                callLogger.logUpstreamError("explain", resp.statusCode(), resp.body());
                http.metrics().recordCall(cfg.modelName(), elapsed, false);
                throw new RuntimeException("LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）");
            }
            http.metrics().recordCall(cfg.modelName(), elapsed, true);
            JsonNode root = objectMapper.readTree(resp.body());
            String content = http.stripJsonFence(http.extractContent(root, anthropic));
            callLogger.logLlmResponse("LLM-explain", cfg.modelName(), elapsed, content);
            return new ExplainResult(objectMapper.readTree(content), cfg.modelName());
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            http.metrics().recordCall(cfg.modelName(), elapsed, false);
            throw e;
        }
    }
}

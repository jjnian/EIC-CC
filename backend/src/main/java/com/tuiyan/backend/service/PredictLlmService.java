package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.model.PredictRequest;
import com.tuiyan.backend.service.llm.GraphPromptBuilder;
import com.tuiyan.backend.service.llm.LlmCallLogger;
import com.tuiyan.backend.service.llm.LlmHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 推演业务 facade：暴露给 {@link PredictionOrchestrator} 的"chain 预测"能力。
 * <p>负责把 PredictRequest 翻译成 LLM 调用，返回完整 chain JSON。
 * <p>同时暴露 {@link #buildPredictPrompt} 让 orchestrator 把 user prompt 写入 Scenario.rawPrompt。
 */
@Service
public class PredictLlmService {

    private static final Logger log = LoggerFactory.getLogger(PredictLlmService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmHttpClient http;
    private final GraphPromptBuilder promptBuilder;
    private final LlmCallLogger callLogger;

    public PredictLlmService(LlmHttpClient http,
                             GraphPromptBuilder promptBuilder,
                             LlmCallLogger callLogger) {
        this.http = http;
        this.promptBuilder = promptBuilder;
        this.callLogger = callLogger;
    }

    /** 构造推演 prompt（system + user）；纯函数，方便 orchestrator 写入 rawPrompt 与调用复用。 */
    public GraphPromptBuilder.PredictPromptArtifact buildPredictPrompt(PredictRequest req) {
        return promptBuilder.buildPredictPrompt(req);
    }

    /**
     * 同步推演调用：构造 prompt → 序列化为请求体 → 同步发起 HTTP → 解析为 JsonNode。
     * <p>由 {@link PredictionOrchestrator} 调用；流式推送是 orchestrator 自己做的，
     * 本方法只负责一次性拿全部 chain。
     */
    public JsonNode predictChain(PredictRequest req) throws IOException {
        LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(req.getModelOverride(), req.getConfigId());
        boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        int steps = req.getSteps() == null ? 4 : Math.max(1, Math.min(10, req.getSteps()));
        boolean backward = "backward".equalsIgnoreCase(req.getIntent());

        log.info("[LLM-predict] 开始推演 model={} url={} direction={} steps={}",
                cfg.modelName(), cfg.baseURL(), backward ? "backward" : "forward", steps);

        GraphPromptBuilder.PredictPromptArtifact artifact = promptBuilder.buildPredictPrompt(req);
        String systemPrompt = artifact.system();
        String userPromptStr = artifact.user();

        callLogger.logConversation("LLM-predict", cfg.modelName(), systemPrompt, null, userPromptStr, null);

        String requestBody = http.buildBody(cfg, systemPrompt, userPromptStr, null, null, false, true);
        log.debug("[LLM-predict] 请求体大小: {} chars", requestBody.length());

        long startTime = System.currentTimeMillis();
        try {
            HttpRequest httpReq = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody);
            HttpResponse<String> resp = http.sendHttp(httpReq, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            if (resp.statusCode() != 200) {
                log.error("[LLM-predict] 请求失败 status={} 耗时={}ms", resp.statusCode(), elapsed);
                callLogger.logUpstreamError("predict", resp.statusCode(), resp.body());
                http.metrics().recordCall(cfg.modelName(), elapsed, false);
                throw new RuntimeException("LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）");
            }

            log.info("[LLM-predict] 请求成功 status=200 耗时={}ms 响应大小={} chars", elapsed, resp.body().length());
            http.metrics().recordCall(cfg.modelName(), elapsed, true);

            JsonNode root = objectMapper.readTree(resp.body());
            String content = http.stripJsonFence(http.extractContent(root, anthropic));
            callLogger.logLlmResponse("LLM-predict", cfg.modelName(), elapsed, content);
            return objectMapper.readTree(content);
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            http.metrics().recordCall(cfg.modelName(), elapsed, false);
            throw e;
        }
    }
}

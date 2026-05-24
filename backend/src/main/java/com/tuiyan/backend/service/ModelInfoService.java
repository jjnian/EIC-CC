package com.tuiyan.backend.service;

import com.tuiyan.backend.config.LlmProperties;
import com.tuiyan.backend.model.ConfigResponse;
import com.tuiyan.backend.model.LlmProvider;
import com.tuiyan.backend.model.dto.ModelTestResponse;
import com.tuiyan.backend.service.llm.LlmHttpClient;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型信息与连通性服务：暴露给 controller 的"列模型 / 拿 ConfigResponse / 测连接"能力。
 * <p>原 LlmService 中与 LLM 业务调用无关、纯粹查配置 / 探测的部分集中到此。
 */
@Service
public class ModelInfoService {

    private final LlmProperties llmProperties;
    private final LlmHttpClient http;

    public ModelInfoService(LlmProperties llmProperties, LlmHttpClient http) {
        this.llmProperties = llmProperties;
        this.http = http;
    }

    /** 返回 application.yml 中配置的全部模型条目（含 apiKey；仅服务端内部使用）。 */
    public List<LlmProperties.ModelEntry> getAllModelConfigs() {
        return llmProperties.getModels();
    }

    /**
     * 构造前端设置页用的完整配置响应：含 provider 元信息列表 + 用户自定义模型列表。
     * <p>顶层 provider/baseUrl/modelName 是 v0.5 之前的兼容字段，新前端会优先用 customModels[0]。
     */
    public ConfigResponse getConfigResponse() {
        List<LlmProperties.ModelEntry> models = llmProperties.getModels();

        String providerCode = "qwen";
        String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        String modelName = "qwen-max";
        if (!models.isEmpty()) {
            LlmProperties.ModelEntry first = models.get(0);
            providerCode = first.getProvider() != null ? first.getProvider() : providerCode;
            baseUrl = first.getBaseUrl() != null ? first.getBaseUrl() : baseUrl;
            modelName = first.getModelName() != null ? first.getModelName() : modelName;
        }

        List<ConfigResponse.ProviderInfo> providers = new ArrayList<>();
        for (LlmProvider p : LlmProvider.values()) {
            providers.add(new ConfigResponse.ProviderInfo(
                p.getCode(),
                p.getDisplayName(),
                p.getBaseUrl(),
                p.getDefaultModel(),
                p.getModels(),
                p.getApiKeyEnvName()
            ));
        }

        List<ConfigResponse.ModelConfigInfo> customModels = new ArrayList<>();
        for (LlmProperties.ModelEntry mc : models) {
            ConfigResponse.ModelConfigInfo info = new ConfigResponse.ModelConfigInfo(
                mc.getId(),
                mc.getName(),
                mc.getBaseUrl(),
                mc.getModelName(),
                mc.isEnabled()
            );
            info.setProvider(mc.getProvider());
            info.setDescription(mc.getDescription());
            info.setContextWindow(mc.getContextWindow());
            info.setMaxOutputTokens(mc.getMaxOutputTokens());
            info.setCapabilities(mc.getCapabilities());
            info.setProtocol(mc.getProtocol());
            customModels.add(info);
        }

        return new ConfigResponse(providerCode, baseUrl, modelName, providers, customModels);
    }

    /**
     * 测试指定模型连通性。失败时返回 status=error 的 {@link ModelTestResponse}，
     * controller 直接 200 返回即可（错误体里已带错误信息）。
     */
    public ModelTestResponse testModel(String modelId) {
        try {
            long latency = testModelConnection(modelId);
            return ModelTestResponse.ok(latency);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ModelTestResponse.error(e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ModelTestResponse.error(msg);
        }
    }

    /**
     * 向指定模型发送最小化请求验证连接可用性。
     * <p>用极短的 "hi" + max_tokens=1 探测；同时把延迟记录到 metrics。
     */
    private long testModelConnection(String modelId) throws Exception {
        LlmProperties.ModelEntry entry = llmProperties.getModels().stream()
            .filter(m -> m.getId().equals(modelId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelId));

        if (!entry.isEnabled()) {
            throw new IllegalStateException("Model is disabled: " + entry.getName());
        }

        String baseUrl = entry.getBaseUrl();
        String apiKey = entry.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("LLM_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing API key for model: " + entry.getName());
        }
        String modelName = entry.getModelName();
        String protocol = entry.getProtocol();

        long start = System.currentTimeMillis();
        try {
            if (http.isAnthropic(baseUrl, modelName, protocol)) {
                testAnthropicConnection(baseUrl, apiKey, modelName);
            } else {
                testOpenAIConnection(baseUrl, apiKey, modelName);
            }
            long latency = System.currentTimeMillis() - start;
            http.metrics().recordCall(modelName, latency, true);
            return latency;
        } catch (Exception e) {
            http.metrics().recordCall(modelName, System.currentTimeMillis() - start, false);
            throw e;
        }
    }

    private void testOpenAIConnection(String baseUrl, String apiKey, String modelName) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        String body = "{\"model\":\"" + modelName + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":1}";

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(15))
            .build();

        HttpResponse<String> resp = http.sendHttp(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() >= 400) {
            String respBody = resp.body();
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + respBody.substring(0, Math.min(200, respBody.length())));
        }
    }

    private void testAnthropicConnection(String baseUrl, String apiKey, String modelName) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/messages";
        String body = "{\"model\":\"" + modelName + "\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", LlmHttpClient.ANTHROPIC_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(15))
            .build();

        HttpResponse<String> resp = http.sendHttp(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() >= 400) {
            String respBody = resp.body();
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + respBody.substring(0, Math.min(200, respBody.length())));
        }
    }
}

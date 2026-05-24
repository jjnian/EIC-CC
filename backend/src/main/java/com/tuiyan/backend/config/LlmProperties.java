package com.tuiyan.backend.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 {@code application.yml} 中 {@code app.llm.*} 读取 LLM 模型配置列表。
 * <p>每个 {@link ModelEntry} 描述一个可用模型（含 baseUrl / apiKey / 协议类型等），
 * 由 {@link com.tuiyan.backend.service.llm.LlmHttpClient} 在调用时按 id 选用。
 */
@Component
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    private List<ModelEntry> models = new ArrayList<>();

    public List<ModelEntry> getModels() { return models; }
    public void setModels(List<ModelEntry> models) { this.models = models; }

    /**
     * 单个模型配置。
     * <p>{@code provider}/{@code protocol} 决定走哪个适配分支：openai 兼容 vs anthropic 原生。
     * apiKey 在 JSON 序列化时通过 {@link JsonProperty.Access#WRITE_ONLY} 屏蔽，避免泄漏给前端。
     */
    public static class ModelEntry {
        private String id;
        private String name;
        private String provider;
        private String baseUrl;
        private String modelName;
        private String apiKey;
        private String protocol;
        private boolean enabled = true;
        private String description;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private List<String> capabilities = new ArrayList<>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        // 反序列化（写入）时仍可接收，但响应给前端时不会被序列化输出，避免 apiKey 外泄
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Integer getContextWindow() { return contextWindow; }
        public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }
        public Integer getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
        public List<String> getCapabilities() { return capabilities; }
        public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
    }
}

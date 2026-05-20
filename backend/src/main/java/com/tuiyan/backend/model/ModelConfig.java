package com.tuiyan.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ModelConfig {
    private String id;
    private String name;
    private String baseUrl;
    private String modelName;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;
    private boolean enabled;
    private long createdAt;
    private long updatedAt;

    // ===== 大模型基本信息 =====
    /** 提供商 code，对应 LlmProvider.code（openai / anthropic / deepseek / qwen 等） */
    private String provider;
    /** 中文简介：擅长什么、定位 */
    private String description;
    /** 上下文窗口大小（token） */
    private Integer contextWindow;
    /** 最大单次输出 token */
    private Integer maxOutputTokens;
    /** 能力标签：vision / json / streaming / tool-use / reasoning */
    private List<String> capabilities;
    /** 协议族：openai (chat/completions) | anthropic (messages)；为空则按 baseUrl/modelName 自动识别 */
    private String protocol;

    public ModelConfig() {}

    public ModelConfig(String name, String baseUrl, String modelName, String apiKey) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.apiKey = apiKey;
        this.enabled = true;
        this.capabilities = new ArrayList<>();
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getContextWindow() { return contextWindow; }
    public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
}

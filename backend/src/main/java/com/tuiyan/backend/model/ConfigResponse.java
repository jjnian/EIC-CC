package com.tuiyan.backend.model;

import java.util.List;

/**
 * 配置接口的响应 DTO，供前端设置页渲染下拉选项与模型卡片用。
 * <p>顶层字段是"全局默认"（兼容旧版前端），列表字段才是真正的多模型 / 多提供商配置：
 * <ul>
 *   <li>{@link ProviderInfo}：预定义提供商（OpenAI、Anthropic 等）的展示元信息；</li>
 *   <li>{@link ModelConfigInfo}：用户在 {@code application.yml} 中实际配置好的模型条目，<b>不含 apiKey</b>。</li>
 * </ul>
 */
public class ConfigResponse {
    // 全局默认 provider 名称（旧字段，新前端会优先用 customModels[0]）
    private String provider;
    private String baseUrl;
    private String modelName;
    // 预定义提供商列表（来自 LlmProvider 枚举）
    private List<ProviderInfo> providers;
    // 用户实际配置的模型列表
    private List<ModelConfigInfo> customModels;

    public ConfigResponse() {}
    public ConfigResponse(String provider, String baseUrl, String modelName, List<ProviderInfo> providers, List<ModelConfigInfo> customModels) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.providers = providers;
        this.customModels = customModels;
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public List<ProviderInfo> getProviders() { return providers; }
    public void setProviders(List<ProviderInfo> providers) { this.providers = providers; }
    public List<ModelConfigInfo> getCustomModels() { return customModels; }
    public void setCustomModels(List<ModelConfigInfo> customModels) { this.customModels = customModels; }

    /**
     * 提供商展示信息，用于前端下拉选择。
     * 数据来源：{@link LlmProvider} 枚举固定项。
     */
    public static class ProviderInfo {
        private String code;
        private String displayName;
        private String baseUrl;
        private String defaultModel;
        private List<String> models;
        // 该 provider 对应的环境变量名（如 OPENAI_API_KEY），用于提示用户配置
        private String apiKeyEnvName;

        public ProviderInfo() {}
        public ProviderInfo(String code, String displayName, String baseUrl, String defaultModel, List<String> models, String apiKeyEnvName) {
            this.code = code;
            this.displayName = displayName;
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel;
            this.models = models;
            this.apiKeyEnvName = apiKeyEnvName;
        }

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getDefaultModel() { return defaultModel; }
        public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
        public List<String> getModels() { return models; }
        public void setModels(List<String> models) { this.models = models; }
        public String getApiKeyEnvName() { return apiKeyEnvName; }
        public void setApiKeyEnvName(String apiKeyEnvName) { this.apiKeyEnvName = apiKeyEnvName; }
    }

    /**
     * 自定义模型配置信息（脱敏：不含 apiKey 字段）。
     * 与 {@link LlmProperties.ModelEntry} 字段一致，但仅给前端读取用。
     */
    public static class ModelConfigInfo {
        private String id;
        private String name;
        private String baseUrl;
        private String modelName;
        private boolean enabled;
        private String provider;
        private String description;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private List<String> capabilities;
        private String protocol;

        public ModelConfigInfo() {}
        public ModelConfigInfo(String id, String name, String baseUrl, String modelName, boolean enabled) {
            this.id = id;
            this.name = name;
            this.baseUrl = baseUrl;
            this.modelName = modelName;
            this.enabled = enabled;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
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
}

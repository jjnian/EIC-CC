package com.tuiyan.backend.model;

import java.util.List;

public class ConfigResponse {
    private String provider;
    private String baseUrl;
    private String modelName;
    private List<ProviderInfo> providers;
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
     * 提供商信息，用于前端下拉选择
     */
    public static class ProviderInfo {
        private String code;
        private String displayName;
        private String baseUrl;
        private String defaultModel;
        private List<String> models;
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
     * 自定义模型配置信息（不含 API Key）
     */
    public static class ModelConfigInfo {
        private String id;
        private String name;
        private String baseUrl;
        private String modelName;
        private boolean enabled;

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
    }
}

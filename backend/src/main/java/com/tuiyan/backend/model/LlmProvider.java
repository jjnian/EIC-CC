package com.tuiyan.backend.model;

import java.util.List;

/**
 * 预定义的大模型提供商配置
 * 所有提供商均采用 OpenAI 兼容的 API 格式
 */
public enum LlmProvider {
    QWEN(
        "qwen",
        "阿里云通义千问",
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "qwen-max",
        "DASHSCOPE_API_KEY",
        List.of("qwen-max", "qwen-plus", "qwen-turbo", "qwen-long", "qwen-coder-plus", "qwen-vl-max", "qwen-vl-plus")
    ),
    DEEPSEEK(
        "deepseek",
        "DeepSeek",
        "https://api.deepseek.com/v1",
        "deepseek-chat",
        "DEEPSEEK_API_KEY",
        List.of("deepseek-chat", "deepseek-reasoner")
    ),
    KIMI(
        "kimi",
        "Kimi (月之暗面)",
        "https://api.moonshot.cn/v1",
        "moonshot-v1-8k",
        "MOONSHOT_API_KEY",
        List.of("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k")
    ),
    GLM(
        "glm",
        "智谱清言 (GLM)",
        "https://open.bigmodel.cn/api/paas/v4",
        "glm-4",
        "ZHIPU_API_KEY",
        List.of("glm-4", "glm-4-plus", "glm-4-air", "glm-4-airx", "glm-4-flash", "glm-4v")
    ),
    MINIMAX(
        "minimax",
        "MiniMax",
        "https://api.minimax.chat/v1",
        "abab6.5s-chat",
        "MINIMAX_API_KEY",
        List.of("abab6.5s-chat", "abab6.5g-chat", "abab6.5t-chat", "abab7-chat", "MiniMax-Text-01")
    ),
    OPENAI(
        "openai",
        "OpenAI",
        "https://api.openai.com/v1",
        "gpt-4o",
        "OPENAI_API_KEY",
        List.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo")
    ),
    CUSTOM(
        "custom",
        "自定义配置",
        "",
        "",
        "LLM_API_KEY",
        List.of()
    );

    private final String code;
    private final String displayName;
    private final String baseUrl;
    private final String defaultModel;
    private final String apiKeyEnvName;
    private final List<String> models;

    LlmProvider(String code, String displayName, String baseUrl, String defaultModel, String apiKeyEnvName, List<String> models) {
        this.code = code;
        this.displayName = displayName;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
        this.apiKeyEnvName = apiKeyEnvName;
        this.models = models;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public String getBaseUrl() { return baseUrl; }
    public String getDefaultModel() { return defaultModel; }
    public String getApiKeyEnvName() { return apiKeyEnvName; }
    public List<String> getModels() { return models; }

    /**
     * 根据 code 查找提供商
     */
    public static LlmProvider fromCode(String code) {
        if (code == null || code.isBlank()) {
            return QWEN; // 默认使用通义千问
        }
        for (LlmProvider provider : values()) {
            if (provider.code.equalsIgnoreCase(code)) {
                return provider;
            }
        }
        return CUSTOM;
    }
}

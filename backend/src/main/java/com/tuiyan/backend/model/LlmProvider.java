package com.tuiyan.backend.model;

import java.util.List;

/**
 * 预定义的大模型提供商配置枚举。
 * <p>大部分提供商采用 OpenAI 兼容的 API 格式；
 * Anthropic (Claude) 使用原生 Messages API，由 {@link com.tuiyan.backend.service.LlmService} 内做协议适配。
 * <p>本枚举仅提供"展示元信息"（baseUrl、默认模型、可选模型列表、对应环境变量名），
 * 不直接持有 apiKey；真正的运行时配置在 {@link com.tuiyan.backend.config.LlmProperties} 中。
 */
public enum LlmProvider {
    OPENAI(
        "openai",
        "OpenAI",
        "https://api.openai.com/v1",
        "gpt-4.1",
        "OPENAI_API_KEY",
        List.of("gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano",
                "gpt-4o", "gpt-4o-mini",
                "o3", "o3-mini", "o4-mini", "o1", "o1-mini")
    ),
    ANTHROPIC(
        "anthropic",
        "Anthropic Claude",
        "https://api.anthropic.com/v1",
        "claude-opus-4-7",
        "ANTHROPIC_API_KEY",
        List.of("claude-opus-4-7", "claude-sonnet-4-6", "claude-haiku-4-5",
                "claude-opus-4-5", "claude-sonnet-4-5",
                "claude-3-7-sonnet-latest", "claude-3-5-haiku-latest")
    ),
    DEEPSEEK(
        "deepseek",
        "DeepSeek",
        "https://api.deepseek.com/v1",
        "deepseek-chat",
        "DEEPSEEK_API_KEY",
        List.of("deepseek-chat", "deepseek-reasoner", "deepseek-coder")
    ),
    QWEN(
        "qwen",
        "阿里云通义千问",
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "qwen-max",
        "DASHSCOPE_API_KEY",
        List.of("qwen-max", "qwen-plus", "qwen-turbo", "qwen-long", "qwen-coder-plus", "qwen-vl-max", "qwen-vl-plus")
    ),
    KIMI(
        "kimi",
        "Kimi (月之暗面)",
        "https://api.moonshot.cn/v1",
        "moonshot-v1-8k",
        "MOONSHOT_API_KEY",
        List.of("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k", "kimi-k2-latest")
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
    ERNIE(
        "ernie",
        "文心一言 (百度)",
        "https://qianfan.baidubce.com/v2",
        "ernie-4.0-turbo-8k",
        "QIANFAN_ACCESS_KEY",
        List.of("ernie-4.0-turbo-8k", "ernie-speed-128k", "ernie-lite-8k", "ernie-tiny-8k")
    ),
    DOUBAO(
        "doubao",
        "豆包 (字节跳动)",
        "https://ark.cn-beijing.volces.com/api/v3",
        "doubao-pro-32k",
        "ARK_API_KEY",
        List.of("doubao-pro-32k", "doubao-pro-128k", "doubao-lite-32k", "doubao-lite-128k")
    ),
    CUSTOM(
        "custom",
        "自定义配置",
        "",
        "",
        "LLM_API_KEY",
        List.of()
    );

    // 枚举字段持有展示元信息；非 final 字段不安全，全部声明 final
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
     * 根据 code 查找提供商。
     * <p>未匹配到时返回 {@link #CUSTOM} 而非 null，方便上层省去判空；
     * code 为空 / null 默认返回 {@link #QWEN}（早期项目偏好通义千问）。
     */
    public static LlmProvider fromCode(String code) {
        if (code == null || code.isBlank()) {
            return QWEN;
        }
        for (LlmProvider provider : values()) {
            if (provider.code.equalsIgnoreCase(code)) {
                return provider;
            }
        }
        return CUSTOM;
    }

    /**
     * 通过 baseURL / modelName 判断是否走 Anthropic Messages API。
     * <p>之所以双重判断：用户可能用自定义反代域名，但 modelName 仍以 claude- 开头；
     * 也可能用 anthropic 官方 URL 调一个非 claude 模型（理论上不存在但兼容）。
     */
    public static boolean isAnthropicEndpoint(String baseURL, String modelName) {
        if (baseURL != null && baseURL.toLowerCase().contains("anthropic.com")) return true;
        if (modelName != null && modelName.toLowerCase().startsWith("claude-")) return true;
        return false;
    }
}

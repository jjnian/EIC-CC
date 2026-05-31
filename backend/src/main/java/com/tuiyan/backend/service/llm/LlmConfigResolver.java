package com.tuiyan.backend.service.llm;

import com.tuiyan.backend.config.LlmProperties;
import com.tuiyan.backend.model.LlmProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 连接配置解析：把 configId / modelOverride / 环境变量 / 默认值按优先级合并，
 * 并判定走 Anthropic Messages 还是 OpenAI Chat Completions 协议。
 * <p>从 {@link LlmHttpClient} 拆出，集中承载配置优先级与协议嗅探逻辑。
 */
@Component
public class LlmConfigResolver {

    private final LlmProperties llmProperties;

    public LlmConfigResolver(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    /**
     * 解析最终连接配置：把 configId / modelOverride / 环境变量 / 默认值按优先级合并。
     * <p>优先级（从高到低）：
     * <ol>
     *   <li>显式 configId 指向的 ModelEntry；</li>
     *   <li>没有 configId 时取 yaml 中的第一项；</li>
     *   <li>yaml 也为空时回退到内置默认（QWEN）；</li>
     *   <li>modelOverride 覆盖 modelName；</li>
     *   <li>环境变量 LLM_BASE_URL / LLM_MODEL_NAME / LLM_API_KEY 进一步覆盖。</li>
     * </ol>
     * 最终 apiKey 仍为空时抛 IllegalStateException。
     */
    public LlmHttpClient.ResolvedConfig resolveConfig(String modelOverride, String configId) {
        String baseURL;
        String modelName;
        String apiKey;
        String protocol = null;
        Integer maxOutputTokens = null;
        boolean rawUrl = false;

        if (configId != null && !configId.isBlank()) {
            LlmProperties.ModelEntry selected = null;
            for (LlmProperties.ModelEntry mc : llmProperties.getModels()) {
                if (mc.getId().equals(configId)) {
                    selected = mc;
                    break;
                }
            }
            if (selected == null) {
                throw new IllegalArgumentException("Model config not found: " + configId);
            }
            if (!selected.isEnabled()) {
                throw new IllegalStateException("Model config is disabled: " + configId);
            }
            baseURL = selected.getBaseUrl();
            modelName = selected.getModelName();
            apiKey = selected.getApiKey();
            protocol = selected.getProtocol();
            maxOutputTokens = selected.getMaxOutputTokens();
            rawUrl = selected.isRawUrl();
        } else {
            List<LlmProperties.ModelEntry> models = llmProperties.getModels();
            if (!models.isEmpty()) {
                LlmProperties.ModelEntry first = models.get(0);
                baseURL = first.getBaseUrl();
                modelName = first.getModelName();
                apiKey = first.getApiKey();
                protocol = first.getProtocol();
                maxOutputTokens = first.getMaxOutputTokens();
                rawUrl = first.isRawUrl();
            } else {
                LlmProvider provider = LlmProvider.QWEN;
                baseURL = provider.getBaseUrl();
                modelName = provider.getDefaultModel();
                apiKey = System.getenv(provider.getApiKeyEnvName());
            }
        }

        if (modelOverride != null && !modelOverride.isBlank()) {
            modelName = modelOverride;
        }

        if (System.getenv("LLM_BASE_URL") != null && !System.getenv("LLM_BASE_URL").isBlank()) {
            baseURL = System.getenv("LLM_BASE_URL");
        }
        if (System.getenv("LLM_MODEL_NAME") != null && !System.getenv("LLM_MODEL_NAME").isBlank()) {
            modelName = System.getenv("LLM_MODEL_NAME");
        }
        if ((apiKey == null || apiKey.isBlank()) && System.getenv("LLM_API_KEY") != null && !System.getenv("LLM_API_KEY").isBlank()) {
            apiKey = System.getenv("LLM_API_KEY");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing API key. Please configure api-key in application.yml or set environment variable: LLM_API_KEY");
        }

        int resolvedMax = (maxOutputTokens != null && maxOutputTokens > 0)
                ? maxOutputTokens : LlmHttpClient.DEFAULT_MAX_OUTPUT_TOKENS;
        return new LlmHttpClient.ResolvedConfig(baseURL, modelName, apiKey, protocol, resolvedMax, rawUrl);
    }

    /**
     * 判断当前调用走 Anthropic Messages 还是 OpenAI Chat Completions。
     * <p>protocol 显式指定时一锤定音；否则根据 baseURL / modelName 嗅探。
     */
    public boolean isAnthropic(String baseURL, String modelName, String protocol) {
        if (protocol != null && !protocol.isBlank()) {
            return "anthropic".equalsIgnoreCase(protocol);
        }
        return LlmProvider.isAnthropicEndpoint(baseURL, modelName);
    }
}

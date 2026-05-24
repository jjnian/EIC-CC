package com.tuiyan.backend.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.config.LlmProperties;
import com.tuiyan.backend.model.LlmProvider;
import com.tuiyan.backend.service.LlmMetricsService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM HTTP 适配层：协议路由 + 请求/响应序列化 + 同步/异步发送 + metrics 记账。
 * <p>对上层 facade 屏蔽 OpenAI vs Anthropic 协议差异：facade 只关心 system / user / history / attachments，
 * 不关心 baseURL 拼接、鉴权头、json_mode 字段名等细节。
 * <p>HttpClient 单例复用连接池；每个请求各自的超时由 {@link #buildHttpRequest} 设置。
 */
@Component
public class LlmHttpClient {

    // Anthropic Messages API 强制要求 anthropic-version 头；本项目固定使用 2023-06-01（稳定版）
    public static final String ANTHROPIC_VERSION = "2023-06-01";
    // 输出 token 上限默认值；本体抽取等长 JSON 任务 8192 容易被截断，调高到 32768
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 32768;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 单例 HttpClient；连接超时只覆盖 TCP 建联阶段，业务超时在每个 request 上单独设
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final LlmProperties llmProperties;
    private final LlmMetricsService metricsService;

    public LlmHttpClient(LlmProperties llmProperties, LlmMetricsService metricsService) {
        this.llmProperties = llmProperties;
        this.metricsService = metricsService;
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    public LlmMetricsService metrics() {
        return metricsService;
    }

    /** 解析后的模型连接配置：endpoint、模型名、API key、协议类型、输出 token 上限。 */
    public record ResolvedConfig(String baseURL, String modelName, String apiKey, String protocol, int maxOutputTokens) {}

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
    public ResolvedConfig resolveConfig(String modelOverride, String configId) {
        String baseURL;
        String modelName;
        String apiKey;
        String protocol = null;
        Integer maxOutputTokens = null;

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
        } else {
            List<LlmProperties.ModelEntry> models = llmProperties.getModels();
            if (!models.isEmpty()) {
                LlmProperties.ModelEntry first = models.get(0);
                baseURL = first.getBaseUrl();
                modelName = first.getModelName();
                apiKey = first.getApiKey();
                protocol = first.getProtocol();
                maxOutputTokens = first.getMaxOutputTokens();
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
                ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;
        return new ResolvedConfig(baseURL, modelName, apiKey, protocol, resolvedMax);
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

    /**
     * 构造 OpenAI 兼容 ChatCompletions 请求体。
     * <p>支持：流式 / 非流式、json_mode、history、图片附件（image_url 形式）。
     * 有图片时 user.content 用数组形式（text 块 + 多个 image_url 块），无图片时退化为字符串。
     */
    public String buildOpenAiBody(String modelName, String systemPrompt, String userText,
                                  List<Map<String, Object>> history,
                                  List<Map<String, Object>> attachments,
                                  boolean stream, boolean jsonMode, int maxTokens) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("stream", stream);
        if (maxTokens > 0) {
            root.put("max_tokens", maxTokens);
        }

        ArrayNode messages = objectMapper.createArrayNode();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sys = objectMapper.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);
        }

        if (history != null) {
            for (Map<String, Object> msg : history) {
                String role = String.valueOf(msg.get("role"));
                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                ObjectNode h = objectMapper.createObjectNode();
                h.put("role", role);
                h.put("content", String.valueOf(msg.get("content")));
                messages.add(h);
            }
        }

        List<Map<String, Object>> imageAtts = filterImageAttachments(attachments);

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        if (imageAtts.isEmpty()) {
            userMsg.put("content", userText == null ? "" : userText);
        } else {
            ArrayNode contentArr = objectMapper.createArrayNode();
            ObjectNode textPart = objectMapper.createObjectNode();
            textPart.put("type", "text");
            textPart.put("text", userText == null ? "" : userText);
            contentArr.add(textPart);
            for (Map<String, Object> img : imageAtts) {
                ObjectNode imgPart = objectMapper.createObjectNode();
                imgPart.put("type", "image_url");
                ObjectNode imgUrl = objectMapper.createObjectNode();
                imgUrl.put("url", String.valueOf(img.get("dataUrl")));
                imgPart.set("image_url", imgUrl);
                contentArr.add(imgPart);
            }
            userMsg.set("content", contentArr);
        }
        messages.add(userMsg);

        root.set("messages", messages);

        if (jsonMode) {
            ObjectNode rf = objectMapper.createObjectNode();
            rf.put("type", "json_object");
            root.set("response_format", rf);
        }
        return objectMapper.writeValueAsString(root);
    }

    /**
     * 构造 Anthropic Messages 请求体。
     * <p>关键差异（vs OpenAI）：
     * <ul>
     *   <li>system 是顶层字段而非 messages[0]；</li>
     *   <li>必须提供 max_tokens；</li>
     *   <li>图片用 base64 source 形式，需要从 dataUrl 中拆出 media_type 和 base64 体。</li>
     * </ul>
     */
    public String buildAnthropicBody(String modelName, String systemPrompt, String userMessage,
                                     List<Map<String, Object>> history,
                                     List<Map<String, Object>> attachments,
                                     boolean stream, int maxTokens) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("max_tokens", maxTokens);
        root.put("stream", stream);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            root.put("system", systemPrompt);
        }

        ArrayNode messages = objectMapper.createArrayNode();
        if (history != null) {
            for (Map<String, Object> h : history) {
                String role = String.valueOf(h.get("role"));
                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                ObjectNode m = objectMapper.createObjectNode();
                m.put("role", role);
                m.put("content", String.valueOf(h.get("content")));
                messages.add(m);
            }
        }

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");

        List<Map<String, Object>> imageAtts = filterImageAttachments(attachments);

        if (imageAtts.isEmpty()) {
            userMsg.put("content", userMessage);
        } else {
            ArrayNode content = objectMapper.createArrayNode();
            for (Map<String, Object> img : imageAtts) {
                String dataUrl = String.valueOf(img.get("dataUrl"));
                String mediaType = "image/jpeg";
                String base64 = dataUrl;
                int comma = dataUrl.indexOf(',');
                if (comma > 0) {
                    String header = dataUrl.substring(0, comma);
                    base64 = dataUrl.substring(comma + 1);
                    int colon = header.indexOf(':');
                    int semi = header.indexOf(';');
                    if (colon >= 0 && semi > colon) {
                        mediaType = header.substring(colon + 1, semi);
                    }
                }
                ObjectNode imgPart = objectMapper.createObjectNode();
                imgPart.put("type", "image");
                ObjectNode source = objectMapper.createObjectNode();
                source.put("type", "base64");
                source.put("media_type", mediaType);
                source.put("data", base64);
                imgPart.set("source", source);
                content.add(imgPart);
            }
            ObjectNode textPart = objectMapper.createObjectNode();
            textPart.put("type", "text");
            textPart.put("text", userMessage);
            content.add(textPart);
            userMsg.set("content", content);
        }
        messages.add(userMsg);
        root.set("messages", messages);

        return objectMapper.writeValueAsString(root);
    }

    /** 按当前 ResolvedConfig 选择正确的协议体构造方法。 */
    public String buildBody(ResolvedConfig cfg, String systemPrompt, String userText,
                            List<Map<String, Object>> history, List<Map<String, Object>> attachments,
                            boolean stream, boolean jsonMode) throws IOException {
        boolean anthropic = isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());
        return anthropic
                ? buildAnthropicBody(cfg.modelName(), systemPrompt, userText, history, attachments, stream, cfg.maxOutputTokens())
                : buildOpenAiBody(cfg.modelName(), systemPrompt, userText, history, attachments, stream, jsonMode, cfg.maxOutputTokens());
    }

    /**
     * 根据协议类型构造 HttpRequest。
     * <p>OpenAI 兼容用 Authorization: Bearer，路径 /chat/completions；
     * Anthropic 用 x-api-key + anthropic-version 头，路径 /messages。
     * 总体超时 90s，覆盖 LLM 推理的最坏情况。
     */
    public HttpRequest buildHttpRequest(String baseURL, String apiKey, boolean anthropic, String requestBody) {
        String url = baseURL.replaceFirst("/+$", "") + (anthropic ? "/messages" : "/chat/completions");
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (anthropic) {
            b.header("x-api-key", apiKey);
            b.header("anthropic-version", ANTHROPIC_VERSION);
        } else {
            b.header("Authorization", "Bearer " + apiKey);
        }
        return b.build();
    }

    /**
     * 从 LLM 响应中抽出文本内容。
     * <p>Anthropic 响应是 content[] 数组，需要拼接所有 type=text 的 block；
     * OpenAI 直接取 choices[0].message.content。
     */
    public String extractContent(JsonNode responseJson, boolean anthropic) {
        if (anthropic) {
            StringBuilder sb = new StringBuilder();
            JsonNode arr = responseJson.path("content");
            if (arr.isArray()) {
                for (JsonNode block : arr) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
            }
            return sb.toString();
        }
        return responseJson.path("choices").path(0).path("message").path("content").asText("");
    }

    /** {@link HttpClient#send} 的 checked-InterruptedException 包装：转成 IOException 让上层不必处理两种异常。 */
    public <T> HttpResponse<T> sendHttp(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) throws IOException {
        try {
            return httpClient.send(request, bodyHandler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP 调用被中断", e);
        }
    }

    /** 剥掉常见的 ```json``` markdown 包装，让上层始终拿到纯 JSON 字符串。 */
    public String stripJsonFence(String content) {
        if (content == null) return "{}";
        String s = content.replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();
        return s.isEmpty() ? "{}" : s;
    }

    private List<Map<String, Object>> filterImageAttachments(List<Map<String, Object>> attachments) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (attachments == null) return out;
        for (Map<String, Object> a : attachments) {
            Object kind = a.get("type");
            Object url = a.get("dataUrl");
            if ("image".equals(String.valueOf(kind)) && url != null && !String.valueOf(url).isBlank()) {
                out.add(a);
            }
        }
        return out;
    }
}

package com.tuiyan.backend.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 请求体构造：OpenAI / Anthropic 两种协议的 JSON 序列化、图片附件过滤、HttpRequest 装配。
 * <p>从 {@link LlmHttpClient} 拆出。协议判定不在本类重复实现，统一委托 {@link LlmConfigResolver#isAnthropic}。
 */
@Component
public class LlmBodyBuilder {

    private static final Logger log = LoggerFactory.getLogger(LlmBodyBuilder.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final LlmConfigResolver configResolver;

    public LlmBodyBuilder(LlmConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    /**
     * 构造 OpenAI 兼容 ChatCompletions 请求体。
     * <p>支持：流式 / 非流式、json_mode、history、图片附件（image_url 形式）。
     * 有图片时 user.content 用数组形式（text 块 + 多个 image_url 块），无图片时退化为字符串。
     */
    public String buildOpenAiBody(String modelName, String systemPrompt, String userText,
                                  List<Map<String, Object>> history,
                                  List<Map<String, Object>> attachments,
                                  boolean stream, boolean jsonMode, int maxTokens,
                                  Double temperature) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("stream", stream);
        if (maxTokens > 0) {
            root.put("max_tokens", maxTokens);
        }
        if (temperature != null) {
            // 显式温度：抽取 / 建模场景传 0 → 关闭随机采样，让同样输入尽量产出一致结果
            root.put("temperature", temperature);
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
                                     boolean stream, int maxTokens,
                                     Double temperature) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("max_tokens", maxTokens);
        root.put("stream", stream);
        if (temperature != null) {
            // Anthropic 温度上限 1.0（OpenAI 为 2.0），统一钳制到 [0,1]
            root.put("temperature", Math.max(0.0, Math.min(temperature, 1.0)));
        }
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

    /** 按当前 ResolvedConfig 选择正确的协议体构造方法（不指定温度，走提供商默认）。 */
    public String buildBody(LlmHttpClient.ResolvedConfig cfg, String systemPrompt, String userText,
                            List<Map<String, Object>> history, List<Map<String, Object>> attachments,
                            boolean stream, boolean jsonMode) throws IOException {
        return buildBody(cfg, systemPrompt, userText, history, attachments, stream, jsonMode, null);
    }

    /**
     * 按当前 ResolvedConfig 选择正确的协议体构造方法。
     * <p>{@code temperature} 非 null 时写入请求体：抽取 / schema→本体等"要稳定"的场景传 {@code 0.0}，
     * 关闭随机采样，让同样输入尽量产出一致结果；为 null 时省略该字段（走提供商默认温度）。
     */
    public String buildBody(LlmHttpClient.ResolvedConfig cfg, String systemPrompt, String userText,
                            List<Map<String, Object>> history, List<Map<String, Object>> attachments,
                            boolean stream, boolean jsonMode, Double temperature) throws IOException {
        boolean anthropic = configResolver.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());
        return anthropic
                ? buildAnthropicBody(cfg.modelName(), systemPrompt, userText, history, attachments, stream, cfg.maxOutputTokens(), temperature)
                : buildOpenAiBody(cfg.modelName(), systemPrompt, userText, history, attachments, stream, jsonMode, cfg.maxOutputTokens(), temperature);
    }

    /**
     * 根据协议类型构造 HttpRequest。
     * <p>OpenAI 兼容用 Authorization: Bearer，路径 /chat/completions；
     * Anthropic 用 x-api-key + anthropic-version 头，路径 /messages。
     * 总体超时 90s，覆盖 LLM 推理的最坏情况。
     */
    public HttpRequest buildHttpRequest(String baseURL, String apiKey, boolean anthropic, String requestBody, boolean rawUrl) {
        String url;
        if (rawUrl) {
            url = baseURL.replaceFirst("/+$", "");
        } else {
            url = baseURL.replaceFirst("/+$", "") + (anthropic ? "/v1/messages" : "/v1/chat/completions");
        }
        log.info("[LLM-http] 请求 URL={} protocol={} rawUrl={}", url, anthropic ? "anthropic" : "openai", rawUrl);
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (anthropic) {
            b.header("x-api-key", apiKey);
            b.header("anthropic-version", LlmHttpClient.ANTHROPIC_VERSION);
            b.header("Authorization", "Bearer " + apiKey);
        } else {
            b.header("Authorization", "Bearer " + apiKey);
        }
        return b.build();
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

package com.tuiyan.backend.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuiyan.backend.service.LlmMetricsService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LLM HTTP 适配层门面：协议路由 + 请求/响应序列化 + 同步/异步发送 + metrics 记账。
 * <p>对上层 facade 屏蔽 OpenAI vs Anthropic 协议差异：facade 只关心 system / user / history / attachments，
 * 不关心 baseURL 拼接、鉴权头、json_mode 字段名等细节。
 * <p>本类为瘦门面：配置解析委派 {@link LlmConfigResolver}、请求体构造委派 {@link LlmBodyBuilder}、
 * 响应解析委派 {@link LlmResponseParser}；仅保留 HttpClient 连接池与同步发送（{@link #sendHttp}）。
 * <p>HttpClient 单例复用连接池；每个请求各自的超时由 {@link LlmBodyBuilder#buildHttpRequest} 设置。
 */
@Component
public class LlmHttpClient {

    // Anthropic Messages API 强制要求 anthropic-version 头；本项目固定使用 2023-06-01（稳定版）
    public static final String ANTHROPIC_VERSION = "2023-06-01";
    // 输出 token 上限默认值；本体抽取等长 JSON 任务 8192 容易被截断，调高到 32768
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 32768;

    // 单例 HttpClient；连接超时只覆盖 TCP 建联阶段，业务超时在每个 request 上单独设
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final LlmMetricsService metricsService;
    private final LlmConfigResolver configResolver;
    private final LlmBodyBuilder bodyBuilder;
    private final LlmResponseParser responseParser;

    public LlmHttpClient(LlmMetricsService metricsService,
                         LlmConfigResolver configResolver,
                         LlmBodyBuilder bodyBuilder,
                         LlmResponseParser responseParser) {
        this.metricsService = metricsService;
        this.configResolver = configResolver;
        this.bodyBuilder = bodyBuilder;
        this.responseParser = responseParser;
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    public LlmMetricsService metrics() {
        return metricsService;
    }

    /** 解析后的模型连接配置：endpoint、模型名、API key、协议类型、输出 token 上限。 */
    public record ResolvedConfig(String baseURL, String modelName, String apiKey, String protocol, int maxOutputTokens, boolean rawUrl) {}

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
        return configResolver.resolveConfig(modelOverride, configId);
    }

    /**
     * 判断当前调用走 Anthropic Messages 还是 OpenAI Chat Completions。
     * <p>protocol 显式指定时一锤定音；否则根据 baseURL / modelName 嗅探。
     */
    public boolean isAnthropic(String baseURL, String modelName, String protocol) {
        return configResolver.isAnthropic(baseURL, modelName, protocol);
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
        return bodyBuilder.buildOpenAiBody(modelName, systemPrompt, userText, history, attachments, stream, jsonMode, maxTokens);
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
        return bodyBuilder.buildAnthropicBody(modelName, systemPrompt, userMessage, history, attachments, stream, maxTokens);
    }

    /** 按当前 ResolvedConfig 选择正确的协议体构造方法。 */
    public String buildBody(ResolvedConfig cfg, String systemPrompt, String userText,
                            List<Map<String, Object>> history, List<Map<String, Object>> attachments,
                            boolean stream, boolean jsonMode) throws IOException {
        return bodyBuilder.buildBody(cfg, systemPrompt, userText, history, attachments, stream, jsonMode);
    }

    /**
     * 根据协议类型构造 HttpRequest。
     * <p>OpenAI 兼容用 Authorization: Bearer，路径 /chat/completions；
     * Anthropic 用 x-api-key + anthropic-version 头，路径 /messages。
     * 总体超时 90s，覆盖 LLM 推理的最坏情况。
     */
    public HttpRequest buildHttpRequest(String baseURL, String apiKey, boolean anthropic, String requestBody, boolean rawUrl) {
        return bodyBuilder.buildHttpRequest(baseURL, apiKey, anthropic, requestBody, rawUrl);
    }

    /**
     * 从 LLM 响应中抽出文本内容。
     * <p>Anthropic 响应是 content[] 数组，需要拼接所有 type=text 的 block；
     * OpenAI 直接取 choices[0].message.content。
     * <p>当配置为 Anthropic 但中转站实际返回 OpenAI 格式时自动回退，避免丢失全部内容。
     */
    public String extractContent(JsonNode responseJson, boolean anthropic) {
        return responseParser.extractContent(responseJson, anthropic);
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
        return responseParser.stripJsonFence(content);
    }
}

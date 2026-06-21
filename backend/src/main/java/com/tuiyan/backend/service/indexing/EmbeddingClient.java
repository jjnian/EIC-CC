package com.tuiyan.backend.service.indexing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.config.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 向量化客户端：调用 OpenAI 兼容的 /v1/embeddings 接口，
 * 将文本转换为浮点向量。
 * <p>支持单条和批量嵌入，批量请求每次最多 20 条文本。
 */
@Service
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final int BATCH_SIZE = 20;
    /** 限流/服务端错误时的最大重试次数（指数退避）。 */
    private static final int MAX_RETRIES = 4;

    private final EmbeddingProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public EmbeddingClient(EmbeddingProperties props) {
        this.props = props;
    }

    /**
     * 检查 Embedding API 是否已配置（baseUrl 和 apiKey 均非空）。
     */
    public boolean isConfigured() {
        return props.getBaseUrl() != null && !props.getBaseUrl().isBlank()
                && props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    /**
     * 对单条文本生成向量嵌入。
     *
     * @param text 待嵌入的文本
     * @return 浮点向量
     * @throws IOException 网络或解析异常
     */
    public float[] embed(String text) throws IOException {
        checkConfigured();
        List<float[]> results = callEmbeddingApi(List.of(text));
        return results.get(0);
    }

    /**
     * 批量生成向量嵌入（超过 20 条自动分批）。
     *
     * @param texts 待嵌入的文本列表
     * @return 与输入等长的向量列表
     * @throws IOException 网络或解析异常
     */
    public List<float[]> embedBatch(List<String> texts) throws IOException {
        checkConfigured();
        if (texts == null || texts.isEmpty()) return List.of();

        List<float[]> allResults = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(i, end);
            List<float[]> batchResults = callEmbeddingApi(batch);
            allResults.addAll(batchResults);
        }
        return allResults;
    }

    private void checkConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("Embedding API 未配置，请设置 EMBEDDING_BASE_URL 和 EMBEDDING_API_KEY 环境变量");
        }
    }

    /**
     * 调用 /v1/embeddings 接口，返回向量列表。
     */
    private List<float[]> callEmbeddingApi(List<String> texts) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", props.getModel());
        ArrayNode inputArr = objectMapper.createArrayNode();
        for (String t : texts) {
            inputArr.add(t);
        }
        body.set("input", inputArr);

        String url = props.getBaseUrl().replaceFirst("/+$", "") + "/v1/embeddings";
        String requestBody = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        log.info("[Embedding] 请求 {} 条文本 → {}", texts.size(), url);

        // 限流(429)/服务端错误(5xx)指数退避重试；其余状态码或重试耗尽即抛出
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Embedding 请求被中断", e);
            }

            int status = response.statusCode();
            if (status == 200) break;

            boolean retryable = (status == 429 || status >= 500);
            if (!retryable || attempt == MAX_RETRIES) {
                log.error("[Embedding] 请求失败 status={} body={}", status,
                        response.body().substring(0, Math.min(500, response.body().length())));
                throw new IOException("Embedding API 调用失败 HTTP " + status);
            }
            long backoffMs = 1000L * (1L << attempt);   // 1s, 2s, 4s, 8s
            log.warn("[Embedding] HTTP {} 第 {}/{} 次重试，{}ms 后重试", status, attempt + 1, MAX_RETRIES, backoffMs);
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Embedding 重试等待被中断", e);
            }
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        JsonNode dataArr = responseJson.path("data");
        if (!dataArr.isArray()) {
            throw new IOException("Embedding API 返回格式异常：缺少 data 数组");
        }

        List<float[]> results = new ArrayList<>();
        for (JsonNode item : dataArr) {
            JsonNode embeddingArr = item.path("embedding");
            if (!embeddingArr.isArray()) {
                throw new IOException("Embedding API 返回格式异常：缺少 embedding 数组");
            }
            float[] vec = new float[embeddingArr.size()];
            for (int i = 0; i < embeddingArr.size(); i++) {
                vec[i] = (float) embeddingArr.get(i).asDouble();
            }
            results.add(vec);
        }

        // 向量条数必须与请求文本数一致，否则 embed()/embedBatch() 会拿到错位或越界的结果
        if (results.size() != texts.size()) {
            throw new IOException("Embedding API 返回向量条数(" + results.size()
                    + ")与请求文本数(" + texts.size() + ")不一致");
        }

        log.info("[Embedding] 成功获取 {} 条向量，维度={}", results.size(),
                results.isEmpty() ? 0 : results.get(0).length);
        return results;
    }
}

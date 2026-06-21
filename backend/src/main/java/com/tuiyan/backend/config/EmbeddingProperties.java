package com.tuiyan.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Embedding 模型配置：从 {@code application.yml} 中 {@code app.embedding.*} 读取。
 * <p>用于文本向量化（RAG 索引 + 检索），支持 OpenAI 兼容的 /v1/embeddings 接口。
 */
@Component
@ConfigurationProperties(prefix = "app.embedding")
public class EmbeddingProperties {

    /** Embedding API 基地址，例如 https://api.openai.com */
    private String baseUrl;
    /** API 密钥 */
    private String apiKey;
    /** 模型名称，例如 text-embedding-3-small */
    private String model;
    /** 向量维度 */
    private int dimension = 1536;
    /** 分块大小（估算 token 数） */
    private int chunkSize = 500;
    /** 相邻分块重叠 token 数 */
    private int chunkOverlap = 50;
    /** 批量索引并发度（同时索引的数据源数量）；过高会触发 embedding 服务限流 */
    private int concurrency = 3;
    /** 启动时是否自动补齐历史未索引内容（index_status≠indexed）。配 embedding 晚于上传的存量数据靠它跟上。 */
    private boolean backfillOnStartup = true;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public int getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public boolean isBackfillOnStartup() { return backfillOnStartup; }
    public void setBackfillOnStartup(boolean backfillOnStartup) { this.backfillOnStartup = backfillOnStartup; }
}

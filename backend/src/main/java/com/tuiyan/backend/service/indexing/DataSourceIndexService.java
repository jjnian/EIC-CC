package com.tuiyan.backend.service.indexing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.EmbeddingProperties;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.repository.DataSourceRepository;
import com.tuiyan.backend.service.connector.FileStoredService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 数据源向量索引服务：读取文件内容 → 分块 → 嵌入 → 存储，并提供相似度检索。
 */
@Service
public class DataSourceIndexService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceIndexService.class);

    private final DataSourceRepository dsRepo;
    private final FileStoredService fileService;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingProperties embeddingProps;
    private final PgVectorSupport pgVector;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record ChunkResult(String content, String dataSourceName, double score) {}

    public DataSourceIndexService(DataSourceRepository dsRepo,
                                  FileStoredService fileService,
                                  EmbeddingClient embeddingClient,
                                  EmbeddingProperties embeddingProps,
                                  PgVectorSupport pgVector,
                                  JdbcTemplate jdbc) {
        this.dsRepo = dsRepo;
        this.fileService = fileService;
        this.embeddingClient = embeddingClient;
        this.embeddingProps = embeddingProps;
        this.pgVector = pgVector;
        this.jdbc = jdbc;
    }

    public boolean isConfigured() {
        return embeddingClient.isConfigured();
    }

    /**
     * 异步索引单个数据源，通过 SSE 推送进度。
     */
    public void indexDataSource(String dataSourceId, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> doIndex(dataSourceId, emitter));
    }

    private void doIndex(String dataSourceId, SseEmitter emitter) {
        try {
            emitStep(emitter, "reading_content", "正在读取数据源内容…");

            DataSourcePO po = dsRepo.findById(dataSourceId);
            if (po == null) {
                emitError(emitter, "数据源不存在: " + dataSourceId);
                return;
            }

            if (!"file_stored".equals(po.getKind())) {
                emitStep(emitter, "skip", "数据库/API 类型暂不支持向量索引");
                emitter.complete();
                return;
            }

            markIndexStatus(dataSourceId, "indexing");

            Map<String, Object> cfg = dsRepo.readConfig(po);
            String content = fileService.readText(cfg, 0, 200_000);
            if (content == null || content.isBlank()) {
                emitStep(emitter, "skip", "数据源无可索引的文本内容");
                markIndexStatus(dataSourceId, "none");
                emitter.complete();
                return;
            }

            emitStep(emitter, "chunking", "正在分割文本… (" + content.length() + " 字符)");
            List<String> chunks = TextChunker.chunk(content,
                    embeddingProps.getChunkSize(), embeddingProps.getChunkOverlap());

            if (chunks.isEmpty()) {
                emitStep(emitter, "skip", "文本分块后无有效内容");
                markIndexStatus(dataSourceId, "none");
                emitter.complete();
                return;
            }

            emitStep(emitter, "clearing_old", "正在清理旧索引…");
            deleteIndex(dataSourceId);

            emitStep(emitter, "embedding", "正在生成向量… (0/" + chunks.size() + ")");
            List<float[]> embeddings = new ArrayList<>();
            int batchSize = 20;
            for (int i = 0; i < chunks.size(); i += batchSize) {
                int end = Math.min(i + batchSize, chunks.size());
                List<String> batch = chunks.subList(i, end);
                List<float[]> batchResult = embeddingClient.embedBatch(batch);
                embeddings.addAll(batchResult);
                emitStep(emitter, "embedding",
                        "正在生成向量… (" + embeddings.size() + "/" + chunks.size() + ")");
            }

            emitStep(emitter, "storing", "正在存储向量…");
            long now = System.currentTimeMillis();
            for (int i = 0; i < chunks.size(); i++) {
                String chunkId = "chk_" + dataSourceId + "_" + i;
                String embId = "emb_" + dataSourceId + "_" + i;
                int tokenCount = TextChunker.estimateTokens(chunks.get(i));

                jdbc.update(
                    "INSERT INTO ds_chunk (id, data_source_id, workspace_id, chunk_index, content, token_count, created_at) VALUES (?,?,?,?,?,?,?)",
                    chunkId, dataSourceId, po.getWorkspaceId(), i, chunks.get(i), tokenCount, now
                );

                String embJson = objectMapper.writeValueAsString(embeddings.get(i));
                jdbc.update(
                    "INSERT INTO ds_embedding (id, chunk_id, embedding, model_name, dimension, created_at) VALUES (?,?,?,?,?,?)",
                    embId, chunkId, embJson, embeddingProps.getModel(),
                    embeddings.get(i).length, now
                );
            }

            // pgvector 可用时，把 TEXT 向量回填到 vector 列（尽力而为，失败则检索回退余弦）
            populateVectorColumn(dataSourceId);

            markIndexStatus(dataSourceId, "indexed");
            emitStep(emitter, "done", "索引完成: " + chunks.size() + " 个文本块");
            emitter.complete();

        } catch (Exception e) {
            log.error("[Index] 索引失败 ds={}: {}", dataSourceId, e.getMessage(), e);
            markIndexStatus(dataSourceId, "error");
            try {
                emitter.send(SseEmitter.event().name("error").data("索引失败: " + e.getMessage()));
            } catch (IOException ignored) {}
            emitter.completeWithError(e);
        }
    }

    /**
     * 在当前工作空间中检索与查询最相关的文本块。
     * <p>pgvector 可用时走 HNSW ANN（近似最近邻），否则回退 TEXT 向量的暴力余弦；
     * ANN 路径异常或空结果时也会兜底到余弦，保证可用性。
     */
    public List<ChunkResult> searchRelevant(String workspaceId, String query, int topK) {
        if (!embeddingClient.isConfigured()) return List.of();

        float[] queryVec;
        try {
            queryVec = embeddingClient.embed(query);
        } catch (IOException e) {
            log.warn("[Index] 查询向量化失败: {}", e.getMessage());
            return List.of();
        }

        if (pgVector.isAvailable()) {
            try {
                List<ChunkResult> ann = searchAnn(workspaceId, queryVec, topK);
                if (!ann.isEmpty()) return ann;
            } catch (Exception e) {
                log.warn("[Index] ANN 检索失败,回退余弦: {}", e.getMessage());
            }
        }
        return searchBruteForce(workspaceId, queryVec, topK);
    }

    /** pgvector HNSW 近似最近邻：用 cosine 距离算子 <=>，score = 1 - 距离。 */
    private List<ChunkResult> searchAnn(String workspaceId, float[] queryVec, int topK) throws Exception {
        String qLit = objectMapper.writeValueAsString(queryVec);   // "[..]"，pgvector 文本字面量
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT c.content, c.data_source_id, 1 - (e.embedding_vec <=> ?::vector) AS score " +
            "FROM ds_chunk c JOIN ds_embedding e ON e.chunk_id = c.id " +
            // 数据源为公共库：召回本工作空间「引用」的数据源（与侧栏/取数口径一致）
            "WHERE c.data_source_id IN (SELECT data_source_id FROM data_source_ref WHERE workspace_id = ?) " +
            "AND e.embedding_vec IS NOT NULL " +
            "ORDER BY e.embedding_vec <=> ?::vector LIMIT ?",
            qLit, workspaceId, qLit, topK);

        Map<String, String> dsNames = new HashMap<>();
        List<ChunkResult> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            double score = ((Number) row.get("score")).doubleValue();
            if (score < 0.1) continue;
            String dsId = (String) row.get("data_source_id");
            String name = dsNames.computeIfAbsent(dsId, id -> {
                DataSourcePO po = dsRepo.findById(id);
                return po != null ? po.getName() : id;
            });
            results.add(new ChunkResult((String) row.get("content"), name, score));
        }
        return results;
    }

    /** TEXT 向量暴力余弦（无 pgvector 时的兜底）。 */
    private List<ChunkResult> searchBruteForce(String workspaceId, float[] queryVec, int topK) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT c.content, c.data_source_id, e.embedding " +
            "FROM ds_chunk c JOIN ds_embedding e ON e.chunk_id = c.id " +
            // 数据源为公共库：召回本工作空间「引用」的数据源（与侧栏/取数口径一致）
            "WHERE c.data_source_id IN (SELECT data_source_id FROM data_source_ref WHERE workspace_id = ?)",
            workspaceId
        );

        if (rows.isEmpty()) return List.of();

        // 获取数据源名称映射
        Map<String, String> dsNames = new HashMap<>();

        record Scored(String content, String dsId, double score) {}
        List<Scored> scored = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String content = (String) row.get("content");
            String dsId = (String) row.get("data_source_id");
            String embStr = (String) row.get("embedding");

            try {
                float[] vec = objectMapper.readValue(embStr, float[].class);
                double sim = cosineSimilarity(queryVec, vec);
                scored.add(new Scored(content, dsId, sim));
            } catch (Exception e) {
                log.warn("[Index] 解析向量失败: {}", e.getMessage());
            }
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        List<ChunkResult> results = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            Scored s = scored.get(i);
            if (s.score < 0.1) break;
            String name = dsNames.computeIfAbsent(s.dsId, id -> {
                DataSourcePO po = dsRepo.findById(id);
                return po != null ? po.getName() : id;
            });
            results.add(new ChunkResult(s.content, name, s.score));
        }
        return results;
    }

    /** 把 TEXT 向量回填到 pgvector 列；扩展不可用或维度不符时安全忽略。 */
    private void populateVectorColumn(String dataSourceId) {
        if (!pgVector.isAvailable()) return;
        try {
            jdbc.update(
                "UPDATE ds_embedding e SET embedding_vec = e.embedding::vector " +
                "FROM ds_chunk c WHERE e.chunk_id = c.id AND c.data_source_id = ? AND e.embedding_vec IS NULL",
                dataSourceId);
        } catch (Exception ex) {
            log.warn("[Index] 回填 vector 列失败(检索将回退余弦): {}", ex.getMessage());
        }
    }

    public Map<String, Object> getIndexStatus(String dataSourceId) {
        DataSourcePO po = dsRepo.findById(dataSourceId);
        String status = (po != null && po.getIndexStatus() != null) ? po.getIndexStatus() : "none";
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ds_chunk WHERE data_source_id = ?",
            Integer.class, dataSourceId
        );
        return Map.of("status", status, "chunkCount", count != null ? count : 0);
    }

    public void deleteIndex(String dataSourceId) {
        jdbc.update("DELETE FROM ds_chunk WHERE data_source_id = ?", dataSourceId);
    }

    private void markIndexStatus(String dataSourceId, String status) {
        jdbc.update("UPDATE data_source SET index_status = ? WHERE id = ?", status, dataSourceId);
    }

    private void emitStep(SseEmitter emitter, String key, String label) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("key", key, "label", label));
            emitter.send(SseEmitter.event().name("step").data(json));
        } catch (IOException e) {
            log.warn("emit step '{}' failed: {}", key, e.toString());
        }
    }

    private void emitError(SseEmitter emitter, String msg) {
        try {
            emitter.send(SseEmitter.event().name("error").data(msg));
        } catch (IOException e) {
            log.warn("emit error failed: {}", e.toString());
        }
        emitter.complete();
    }

    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }
}

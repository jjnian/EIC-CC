package com.tuiyan.backend.service.indexing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图节点向量索引：把本体图节点的 label 向量化入库（graph_node_embedding），供「对话改图」在大图上
 * <b>语义检索定位相关节点</b>——查询走 pgvector HNSW，<b>无需把整图拉进内存</b>，任意规模都是索引级检索。
 * <p>照搬 {@link ExperienceIndexService} 的存/查/兜底：embedding(TEXT) 为真值，embedding_vec 为 HNSW 加速副本；
 * 无 pgvector 时暴力余弦兜底。全程 best-effort：embedding 未配置 / 失败 → 静默降级，绝不打断建图/改图。
 */
@Service
public class GraphNodeIndexService {

    private static final Logger log = LoggerFactory.getLogger(GraphNodeIndexService.class);

    /** 单次 embedBatch 的分片大小，防单次请求过大。 */
    private static final int EMBED_CHUNK = 128;
    /** 检索命中的最低相似度阈值。 */
    private static final double MIN_SCORE = 0.15;

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddingClient;
    private final PgVectorSupport pgVector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GraphNodeIndexService(JdbcTemplate jdbc, EmbeddingClient embeddingClient, PgVectorSupport pgVector) {
        this.jdbc = jdbc;
        this.embeddingClient = embeddingClient;
        this.pgVector = pgVector;
    }

    public boolean isConfigured() { return embeddingClient.isConfigured(); }

    /** 全量重建某模型的节点向量索引（建图后调用）。异步 + best-effort，不阻塞建图返回。 */
    @org.springframework.scheduling.annotation.Async("appTaskExecutor")
    public void reindex(String modelId, List<Map<String, Object>> nodes) {
        if (!embeddingClient.isConfigured() || modelId == null || nodes == null || nodes.isEmpty()) return;
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Map<String, Object> n : nodes) {
            String id = str(n.get("id")), label = str(n.get("label"));
            if (!id.isEmpty() && !label.isBlank()) { ids.add(id); labels.add(label); }
        }
        if (ids.isEmpty()) return;
        try {
            jdbc.update("DELETE FROM graph_node_embedding WHERE model_id = ?", modelId);
            long now = System.currentTimeMillis();
            for (int off = 0; off < ids.size(); off += EMBED_CHUNK) {
                int end = Math.min(off + EMBED_CHUNK, ids.size());
                List<String> chunkLabels = labels.subList(off, end);
                List<float[]> vecs = embeddingClient.embedBatch(chunkLabels);
                if (vecs.size() != chunkLabels.size()) continue;
                for (int i = 0; i < vecs.size(); i++) {
                    float[] v = vecs.get(i);
                    String nodeId = ids.get(off + i);
                    jdbc.update("INSERT INTO graph_node_embedding(id, model_id, node_id, label, embedding, dimension, created_at) "
                                    + "VALUES(?,?,?,?,?,?,?) ON CONFLICT (id) DO UPDATE SET label=EXCLUDED.label, "
                                    + "embedding=EXCLUDED.embedding, dimension=EXCLUDED.dimension, created_at=EXCLUDED.created_at",
                            modelId + "|" + nodeId, modelId, nodeId, chunkLabels.get(i),
                            objectMapper.writeValueAsString(v), v.length, now);
                }
            }
            populateVec(modelId);
            log.info("[graph-node-index] 已建节点向量索引 model={} 节点={}", modelId, ids.size());
        } catch (Exception e) {
            log.warn("[graph-node-index] 建索引失败(降级，不影响建图): {}", e.toString());
        }
    }

    /** 局部同步单个节点（对话/patch 改动后）。best-effort。 */
    public void upsertNode(String modelId, String nodeId, String label) {
        if (!embeddingClient.isConfigured() || modelId == null || nodeId == null || nodeId.isBlank()
                || label == null || label.isBlank()) return;
        try {
            float[] v = embeddingClient.embed(label);
            jdbc.update("INSERT INTO graph_node_embedding(id, model_id, node_id, label, embedding, dimension, created_at) "
                            + "VALUES(?,?,?,?,?,?,?) ON CONFLICT (id) DO UPDATE SET label=EXCLUDED.label, "
                            + "embedding=EXCLUDED.embedding, dimension=EXCLUDED.dimension, created_at=EXCLUDED.created_at",
                    modelId + "|" + nodeId, modelId, nodeId, label,
                    objectMapper.writeValueAsString(v), v.length, System.currentTimeMillis());
            if (pgVector.isAvailable()) {
                jdbc.update("UPDATE graph_node_embedding SET embedding_vec = embedding::vector WHERE id = ?", modelId + "|" + nodeId);
            }
        } catch (Exception e) {
            log.debug("[graph-node-index] 单点同步失败(忽略): {}", e.toString());
        }
    }

    /** 删除单个节点的向量（节点删除后）。best-effort。 */
    public void deleteNode(String modelId, String nodeId) {
        if (modelId == null || nodeId == null) return;
        try { jdbc.update("DELETE FROM graph_node_embedding WHERE id = ?", modelId + "|" + nodeId); }
        catch (Exception ignore) {}
    }

    /** 删除整个模型的向量索引（模型删除后）。best-effort。 */
    public void deleteModel(String modelId) {
        if (modelId == null) return;
        try { jdbc.update("DELETE FROM graph_node_embedding WHERE model_id = ?", modelId); }
        catch (Exception ignore) {}
    }

    /**
     * 语义检索：返回与 query 最相关的节点 id（≤ topK，按相似度降序），<b>不加载整图</b>。
     * 索引为空 / 未配置 embedding / 检索失败 → 返回空列表（调用方降级）。
     */
    public List<String> search(String modelId, String query, int topK) {
        if (!embeddingClient.isConfigured() || modelId == null || query == null || query.isBlank()) return List.of();
        float[] qv;
        try { qv = embeddingClient.embed(query); }
        catch (Exception e) { return List.of(); }

        if (pgVector.isAvailable()) {
            try {
                String qLit = objectMapper.writeValueAsString(qv);
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT node_id, 1 - (embedding_vec <=> ?::vector) AS score FROM graph_node_embedding "
                                + "WHERE model_id = ? AND embedding_vec IS NOT NULL "
                                + "ORDER BY embedding_vec <=> ?::vector LIMIT ?",
                        qLit, modelId, qLit, topK);
                List<String> out = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    double s = ((Number) r.get("score")).doubleValue();
                    if (s >= MIN_SCORE) out.add((String) r.get("node_id"));
                }
                if (!out.isEmpty()) return out;
            } catch (Exception e) {
                log.debug("[graph-node-index] ANN 检索失败，回退暴力: {}", e.toString());
            }
        }
        return searchBruteForce(modelId, qv, topK);
    }

    private List<String> searchBruteForce(String modelId, float[] qv, int topK) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT node_id, embedding FROM graph_node_embedding WHERE model_id = ?", modelId);
            record Scored(String id, double score) {}
            List<Scored> scored = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                try {
                    float[] v = objectMapper.readValue((String) r.get("embedding"), float[].class);
                    double s = cosine(qv, v);
                    if (s >= MIN_SCORE) scored.add(new Scored((String) r.get("node_id"), s));
                } catch (Exception ignore) {}
            }
            scored.sort((a, b) -> Double.compare(b.score(), a.score()));
            List<String> out = new ArrayList<>();
            for (int i = 0; i < Math.min(topK, scored.size()); i++) out.add(scored.get(i).id());
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 把 TEXT embedding 灌进 vector 列供 HNSW 用。 */
    private void populateVec(String modelId) {
        if (!pgVector.isAvailable()) return;
        try {
            jdbc.update("UPDATE graph_node_embedding SET embedding_vec = embedding::vector "
                    + "WHERE model_id = ? AND embedding_vec IS NULL", modelId);
        } catch (Exception e) {
            log.debug("[graph-node-index] 灌 vector 列失败(忽略): {}", e.toString());
        }
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
}

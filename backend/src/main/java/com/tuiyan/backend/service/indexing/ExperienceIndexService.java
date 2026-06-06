package com.tuiyan.backend.service.indexing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.EmbeddingProperties;
import com.tuiyan.backend.entity.ExperiencePO;
import com.tuiyan.backend.repository.ExperienceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 经验库向量索引服务：经验正文 → 分块 → 嵌入 → 存储（exp_chunk / exp_embedding），并提供相似度检索。
 * <p>与 {@link DataSourceIndexService} 平行：差别在于文本直接取自 experience 行（无需读文件），
 * 且检索结果以经验标题作为来源名，便于在 prompt 里与数据源内容区分。
 * <p>经验体量小、保存频繁，因此采用「保存后自动重建索引」而非手动触发。
 */
@Service
public class ExperienceIndexService {

    private static final Logger log = LoggerFactory.getLogger(ExperienceIndexService.class);

    private final ExperienceRepository expRepo;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingProperties embeddingProps;
    private final PgVectorSupport pgVector;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 检索命中片段：正文 + 所属经验标题 + 相似度。 */
    public record ChunkResult(String content, String experienceTitle, double score) {}

    public ExperienceIndexService(ExperienceRepository expRepo,
                                  EmbeddingClient embeddingClient,
                                  EmbeddingProperties embeddingProps,
                                  PgVectorSupport pgVector,
                                  JdbcTemplate jdbc) {
        this.expRepo = expRepo;
        this.embeddingClient = embeddingClient;
        this.embeddingProps = embeddingProps;
        this.pgVector = pgVector;
        this.jdbc = jdbc;
    }

    public boolean isConfigured() {
        return embeddingClient.isConfigured();
    }

    /** 保存后触发的异步重建索引；未配置 embedding 时静默跳过。 */
    public void reindexAsync(String experienceId) {
        if (!isConfigured()) return;
        CompletableFuture.runAsync(() -> {
            try {
                reindex(experienceId);
            } catch (Exception e) {
                log.warn("[ExpIndex] 异步索引失败 exp={}: {}", experienceId, e.getMessage());
            }
        });
    }

    /**
     * 同步重建单条经验的索引：清旧块 → 分块 → 嵌入 → 落库，并更新 index_status。
     */
    public void reindex(String experienceId) {
        ExperiencePO po = expRepo.findById(experienceId);
        if (po == null) return;

        // 标题也并入正文一起索引，让"按标题召回"也能命中
        String body = (po.getContent() == null ? "" : po.getContent());
        String text = (po.getTitle() == null || po.getTitle().isBlank())
                ? body
                : po.getTitle() + "\n\n" + body;

        if (text.isBlank()) {
            // 内容被清空：删掉残留索引（旧块在重建路径里才删，这里单独处理空内容）
            deleteIndex(experienceId);
            markIndexStatus(experienceId, "none");
            return;
        }

        markIndexStatus(experienceId, "indexing");
        try {
            // 结构感知切块：按 Markdown 标题层级 + 大小约束
            List<String> chunks = TextChunker.chunkStructured(text,
                    embeddingProps.getChunkSize(), embeddingProps.getChunkOverlap());
            if (chunks.isEmpty()) {
                deleteIndex(experienceId);
                markIndexStatus(experienceId, "none");
                return;
            }

            // 增量复用：同模型下,内容哈希未变的块直接复用旧向量,只对新增/改动块调 embedding
            Map<String, String> reusable = loadReusableByHash(experienceId);
            List<String> hashes = new ArrayList<>(chunks.size());
            List<Integer> toEmbed = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String h = sha256(chunks.get(i));
                hashes.add(h);
                if (!reusable.containsKey(h)) toEmbed.add(i);
            }
            Map<String, String> freshByHash = new HashMap<>();
            if (!toEmbed.isEmpty()) {
                List<String> batch = toEmbed.stream().map(chunks::get).toList();
                List<float[]> vecs = embeddingClient.embedBatch(batch);
                for (int j = 0; j < toEmbed.size(); j++) {
                    freshByHash.put(hashes.get(toEmbed.get(j)),
                            objectMapper.writeValueAsString(vecs.get(j)));
                }
            }

            // 旧块全部重建（复用的块带着旧向量重新落库,顺序/索引随当前内容刷新）
            deleteIndex(experienceId);
            long now = System.currentTimeMillis();
            for (int i = 0; i < chunks.size(); i++) {
                String h = hashes.get(i);
                String embJson = reusable.getOrDefault(h, freshByHash.get(h));
                if (embJson == null) continue;   // 兜底,理论上不会发生
                String chunkId = "echk_" + experienceId + "_" + i;
                String embId = "eemb_" + experienceId + "_" + i;
                jdbc.update(
                    "INSERT INTO exp_chunk (id, experience_id, workspace_id, chunk_index, content, content_hash, token_count, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    chunkId, experienceId, po.getWorkspaceId(), i, chunks.get(i), h,
                    TextChunker.estimateTokens(chunks.get(i)), now
                );
                jdbc.update(
                    "INSERT INTO exp_embedding (id, chunk_id, embedding, model_name, dimension, created_at) VALUES (?,?,?,?,?,?)",
                    embId, chunkId, embJson, embeddingProps.getModel(), embeddingProps.getDimension(), now
                );
            }

            // pgvector 可用时,把 TEXT 向量回填到 vector 列(尽力而为,维度不符则忽略,检索回退余弦)
            populateVectorColumn(experienceId);

            markIndexStatus(experienceId, "indexed");
            log.info("[ExpIndex] 索引完成 exp={} chunks={} (复用 {}, 新算 {})",
                    experienceId, chunks.size(), chunks.size() - toEmbed.size(), toEmbed.size());
        } catch (Exception e) {
            log.error("[ExpIndex] 索引失败 exp={}: {}", experienceId, e.getMessage(), e);
            markIndexStatus(experienceId, "error");
        }
    }

    /** 取该经验现有「内容哈希 → 向量 JSON」映射,仅限当前 embedding 模型,用于增量复用。 */
    private Map<String, String> loadReusableByHash(String experienceId) {
        Map<String, String> out = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.content_hash AS h, e.embedding AS emb " +
                "FROM exp_chunk c JOIN exp_embedding e ON e.chunk_id = c.id " +
                "WHERE c.experience_id = ? AND c.content_hash IS NOT NULL AND e.model_name = ?",
                experienceId, embeddingProps.getModel());
            for (Map<String, Object> r : rows) {
                Object h = r.get("h");
                Object emb = r.get("emb");
                if (h != null && emb != null) out.putIfAbsent(String.valueOf(h), String.valueOf(emb));
            }
        } catch (Exception e) {
            log.warn("[ExpIndex] 读取可复用向量失败(将全量重算): {}", e.getMessage());
        }
        return out;
    }

    /** 把 TEXT 向量回填到 pgvector 列;扩展不可用或维度不符时安全忽略。 */
    private void populateVectorColumn(String experienceId) {
        if (!pgVector.isAvailable()) return;
        try {
            jdbc.update(
                "UPDATE exp_embedding e SET embedding_vec = e.embedding::vector " +
                "FROM exp_chunk c WHERE e.chunk_id = c.id AND c.experience_id = ? AND e.embedding_vec IS NULL",
                experienceId);
        } catch (Exception ex) {
            log.warn("[ExpIndex] 回填 vector 列失败(检索将回退余弦): {}", ex.getMessage());
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /**
     * 在指定工作空间内检索与查询最相关的经验片段。
     * <p>pgvector 可用时走 HNSW ANN（近似最近邻），否则回退 TEXT 向量的暴力余弦；
     * ANN 路径异常或空结果时也会兜底到余弦，保证可用性。
     */
    public List<ChunkResult> searchRelevant(String workspaceId, String query, int topK) {
        if (!embeddingClient.isConfigured()) return List.of();

        float[] queryVec;
        try {
            queryVec = embeddingClient.embed(query);
        } catch (IOException e) {
            log.warn("[ExpIndex] 查询向量化失败: {}", e.getMessage());
            return List.of();
        }

        if (pgVector.isAvailable()) {
            try {
                List<ChunkResult> ann = searchAnn(workspaceId, queryVec, topK);
                if (!ann.isEmpty()) return ann;
            } catch (Exception e) {
                log.warn("[ExpIndex] ANN 检索失败,回退余弦: {}", e.getMessage());
            }
        }
        return searchBruteForce(workspaceId, queryVec, topK);
    }

    /** pgvector HNSW 近似最近邻：用 cosine 距离算子 <=>，score = 1 - 距离。 */
    private List<ChunkResult> searchAnn(String workspaceId, float[] queryVec, int topK) throws Exception {
        String qLit = objectMapper.writeValueAsString(queryVec);   // "[..]"，pgvector 文本字面量
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT c.content, c.experience_id, 1 - (e.embedding_vec <=> ?::vector) AS score " +
            "FROM exp_chunk c JOIN exp_embedding e ON e.chunk_id = c.id " +
            "WHERE c.workspace_id = ? AND e.embedding_vec IS NOT NULL " +
            "ORDER BY e.embedding_vec <=> ?::vector LIMIT ?",
            qLit, workspaceId, qLit, topK);

        Map<String, String> titles = new HashMap<>();
        List<ChunkResult> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            double score = ((Number) row.get("score")).doubleValue();
            if (score < 0.1) continue;
            String expId = (String) row.get("experience_id");
            String title = titles.computeIfAbsent(expId, id -> {
                ExperiencePO po = expRepo.findById(id);
                return po != null ? po.getTitle() : id;
            });
            results.add(new ChunkResult((String) row.get("content"), title, score));
        }
        return results;
    }

    /** TEXT 向量暴力余弦（无 pgvector 时的兜底）。 */
    private List<ChunkResult> searchBruteForce(String workspaceId, float[] queryVec, int topK) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT c.content, c.experience_id, e.embedding " +
            "FROM exp_chunk c JOIN exp_embedding e ON e.chunk_id = c.id " +
            "WHERE c.workspace_id = ?",
            workspaceId
        );
        if (rows.isEmpty()) return List.of();

        record Scored(String content, String expId, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String content = (String) row.get("content");
            String expId = (String) row.get("experience_id");
            String embStr = (String) row.get("embedding");
            try {
                float[] vec = objectMapper.readValue(embStr, float[].class);
                double sim = DataSourceIndexService.cosineSimilarity(queryVec, vec);
                scored.add(new Scored(content, expId, sim));
            } catch (Exception e) {
                log.warn("[ExpIndex] 解析向量失败: {}", e.getMessage());
            }
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        Map<String, String> titles = new HashMap<>();
        List<ChunkResult> results = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            Scored s = scored.get(i);
            if (s.score < 0.1) break;
            String title = titles.computeIfAbsent(s.expId, id -> {
                ExperiencePO po = expRepo.findById(id);
                return po != null ? po.getTitle() : id;
            });
            results.add(new ChunkResult(s.content, title, s.score));
        }
        return results;
    }

    public Map<String, Object> getIndexStatus(String experienceId) {
        ExperiencePO po = expRepo.findById(experienceId);
        String status = (po != null && po.getIndexStatus() != null) ? po.getIndexStatus() : "none";
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM exp_chunk WHERE experience_id = ?", Integer.class, experienceId);
        return Map.of("status", status, "chunkCount", count != null ? count : 0);
    }

    public void deleteIndex(String experienceId) {
        jdbc.update("DELETE FROM exp_chunk WHERE experience_id = ?", experienceId);
    }

    private void markIndexStatus(String experienceId, String status) {
        jdbc.update("UPDATE experience SET index_status = ? WHERE id = ?", status, experienceId);
    }
}

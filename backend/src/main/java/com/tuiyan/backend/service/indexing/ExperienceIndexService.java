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
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 检索命中片段：正文 + 所属经验标题 + 相似度。 */
    public record ChunkResult(String content, String experienceTitle, double score) {}

    public ExperienceIndexService(ExperienceRepository expRepo,
                                  EmbeddingClient embeddingClient,
                                  EmbeddingProperties embeddingProps,
                                  JdbcTemplate jdbc) {
        this.expRepo = expRepo;
        this.embeddingClient = embeddingClient;
        this.embeddingProps = embeddingProps;
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

        // 先清旧索引，避免编辑后残留过时片段
        deleteIndex(experienceId);

        if (text.isBlank()) {
            markIndexStatus(experienceId, "none");
            return;
        }

        markIndexStatus(experienceId, "indexing");
        try {
            List<String> chunks = TextChunker.chunk(text,
                    embeddingProps.getChunkSize(), embeddingProps.getChunkOverlap());
            if (chunks.isEmpty()) {
                markIndexStatus(experienceId, "none");
                return;
            }

            List<float[]> embeddings = embeddingClient.embedBatch(chunks);
            long now = System.currentTimeMillis();
            for (int i = 0; i < chunks.size(); i++) {
                String chunkId = "echk_" + experienceId + "_" + i;
                String embId = "eemb_" + experienceId + "_" + i;
                int tokenCount = TextChunker.estimateTokens(chunks.get(i));

                jdbc.update(
                    "INSERT INTO exp_chunk (id, experience_id, workspace_id, chunk_index, content, token_count, created_at) VALUES (?,?,?,?,?,?,?)",
                    chunkId, experienceId, po.getWorkspaceId(), i, chunks.get(i), tokenCount, now
                );
                String embJson = objectMapper.writeValueAsString(embeddings.get(i));
                jdbc.update(
                    "INSERT INTO exp_embedding (id, chunk_id, embedding, model_name, dimension, created_at) VALUES (?,?,?,?,?,?)",
                    embId, chunkId, embJson, embeddingProps.getModel(), embeddings.get(i).length, now
                );
            }
            markIndexStatus(experienceId, "indexed");
            log.info("[ExpIndex] 索引完成 exp={} chunks={}", experienceId, chunks.size());
        } catch (Exception e) {
            log.error("[ExpIndex] 索引失败 exp={}: {}", experienceId, e.getMessage(), e);
            markIndexStatus(experienceId, "error");
        }
    }

    /**
     * 在指定工作空间内检索与查询最相关的经验片段。
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

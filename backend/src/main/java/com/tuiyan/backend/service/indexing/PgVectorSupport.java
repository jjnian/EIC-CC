package com.tuiyan.backend.service.indexing;

import com.tuiyan.backend.config.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * pgvector 能力探测与启用：在应用就绪后「尝试」开启 vector 扩展并为 exp_embedding / ds_embedding 建 HNSW 索引。
 * <p>关键约束：主 schema（init.sql）配置 {@code continue-on-error: false}，任何失败都会中断启动，
 * 所以 vector 相关 DDL 绝不能放进 init.sql。这里在 try-catch 中按需启用——
 * <ul>
 *   <li>装了 vector 扩展 → {@link #isAvailable()} 为 true，检索走 ANN（近似最近邻）；</li>
 *   <li>没装 / 没权限 → 静默回退，检索继续用 TEXT 向量的暴力余弦，应用照常启动。</li>
 * </ul>
 * TEXT 列 {@code embedding} 始终是真值来源，vector 列只是加速副本。
 */
@Component
public class PgVectorSupport {

    private static final Logger log = LoggerFactory.getLogger(PgVectorSupport.class);

    private final JdbcTemplate jdbc;
    private final EmbeddingProperties props;
    private volatile boolean available = false;

    public PgVectorSupport(JdbcTemplate jdbc, EmbeddingProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public boolean isAvailable() {
        return available;
    }

    /** 当前 vector 列的维度（与 embedding 模型维度需一致）。 */
    public int dimension() {
        return props.getDimension() > 0 ? props.getDimension() : 1536;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void tryEnable() {
        int dim = dimension();
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

            // 经验库：exp_embedding
            jdbc.execute("ALTER TABLE exp_embedding ADD COLUMN IF NOT EXISTS embedding_vec vector(" + dim + ")");
            // HNSW：cosine 距离算子，与检索时的 <=> 一致
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_exp_embedding_vec "
                    + "ON exp_embedding USING hnsw (embedding_vec vector_cosine_ops)");

            // 数据源：ds_embedding（与经验库平行）
            jdbc.execute("ALTER TABLE ds_embedding ADD COLUMN IF NOT EXISTS embedding_vec vector(" + dim + ")");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ds_embedding_vec "
                    + "ON ds_embedding USING hnsw (embedding_vec vector_cosine_ops)");

            available = true;
            log.info("[pgvector] 已启用 ANN 检索 (HNSW, dim={})", dim);
        } catch (Exception e) {
            available = false;
            log.info("[pgvector] 未启用（回退暴力余弦检索）：{}", e.getMessage());
        }
    }
}

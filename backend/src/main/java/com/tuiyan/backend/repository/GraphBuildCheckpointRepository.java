package com.tuiyan.backend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 建图作业断点续跑的 checkpoint 存储：每批抽取的「处理后产出」按 (作业签名, 批哈希) 落盘,
 * 重跑时命中即复用、跳过重复 LLM 调用。用 {@link JdbcTemplate} 直连(简单 KV upsert,无需 ORM)。
 * <p>并发安全:批抽取在多线程并行,{@link #save} 用 {@code ON CONFLICT DO NOTHING} 幂等;读互不干扰。
 */
@Repository
public class GraphBuildCheckpointRepository {

    private final JdbcTemplate jdbc;

    public GraphBuildCheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 取某批已缓存的产出 JSON;无则 null。 */
    public String find(String jobSig, String batchHash) {
        List<String> rows = jdbc.query(
                "SELECT fragment_json FROM graph_build_checkpoint WHERE job_sig = ? AND batch_hash = ?",
                (rs, i) -> rs.getString(1), jobSig, batchHash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 落盘一批产出(已存在则不覆盖:同 (jobSig,batchHash) 输入一致,产出等价)。 */
    public void save(String jobSig, String batchHash, String workspaceId, String fragmentJson) {
        jdbc.update(
                "INSERT INTO graph_build_checkpoint (job_sig, batch_hash, workspace_id, fragment_json, created_at) " +
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT (job_sig, batch_hash) DO NOTHING",
                jobSig, batchHash, workspaceId, fragmentJson, System.currentTimeMillis());
    }

    /** 作业成功落库后清理其全部 checkpoint。返回删除行数。 */
    public int deleteJob(String jobSig) {
        return jdbc.update("DELETE FROM graph_build_checkpoint WHERE job_sig = ?", jobSig);
    }

    /** 按龄清理孤儿(仅 extract 预览或崩溃后未走到清理的残留)。返回删除行数。 */
    public int pruneOlderThan(long cutoffEpochMs) {
        return jdbc.update("DELETE FROM graph_build_checkpoint WHERE created_at < ?", cutoffEpochMs);
    }
}

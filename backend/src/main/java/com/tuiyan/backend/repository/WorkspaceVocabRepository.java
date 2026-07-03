package com.tuiyan.backend.repository;

import com.tuiyan.backend.entity.WorkspaceVocabPO;
import com.tuiyan.backend.mapper.WorkspaceVocabMapper;
import org.springframework.stereotype.Repository;

/**
 * 词表骨架仓储：每工作空间一行（主键即 workspaceId），读写都很薄。
 * 工作空间删除时由外键级联自动清理。
 */
@Repository
public class WorkspaceVocabRepository {

    private final WorkspaceVocabMapper mapper;

    public WorkspaceVocabRepository(WorkspaceVocabMapper mapper) {
        this.mapper = mapper;
    }

    /** 读取工作空间的词表 JSON；无记录返回 null。 */
    public String findVocabJson(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) return null;
        WorkspaceVocabPO po = mapper.selectById(workspaceId);
        return po == null ? null : po.getVocabJson();
    }

    /** 覆盖保存工作空间词表（upsert）。 */
    public void save(String workspaceId, String vocabJson, int sourceCount) {
        if (workspaceId == null || workspaceId.isBlank() || vocabJson == null) return;
        WorkspaceVocabPO po = new WorkspaceVocabPO();
        po.setWorkspaceId(workspaceId);
        po.setVocabJson(vocabJson);
        po.setSourceCount(sourceCount);
        po.setUpdatedAt(System.currentTimeMillis());
        if (mapper.selectById(workspaceId) == null) {
            mapper.insert(po);
        } else {
            mapper.updateById(po);
        }
    }
}

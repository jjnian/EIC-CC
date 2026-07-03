package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 本体词表骨架（Schema-First 建图的规约产物）：每工作空间一份受控词表。
 * <p>{@code vocabJson} 为 {@code {vocab:[{canonical,type,aliases[]}]}} 的 JSON 文本。
 * 批抽取时作为命名规约注入 prompt，消除跨批命名漂移；增量建图复用，全量建图重建覆盖。
 */
@TableName("workspace_vocab")
public class WorkspaceVocabPO {

    @TableId
    private String workspaceId;
    private String vocabJson;
    /** 构建该词表时采样的经验篇数（观测用）。 */
    private Integer sourceCount;
    private Long updatedAt;

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getVocabJson() { return vocabJson; }
    public void setVocabJson(String vocabJson) { this.vocabJson = vocabJson; }
    public Integer getSourceCount() { return sourceCount; }
    public void setSourceCount(Integer sourceCount) { this.sourceCount = sourceCount; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}

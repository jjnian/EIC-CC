package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 假设模板 PO，对应 hypothesis_template 表。
 * <p>seeds / constraints 序列化为 JSON 字符串存储。
 */
@TableName("hypothesis_template")
public class HypothesisTemplatePO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String modelId;
    private String name;
    private String intent;
    private Integer steps;
    private String prompt;
    private String seedsJson;
    private String constraintsJson;
    private Long createdAt;
    private Long lastUsedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public Integer getSteps() { return steps; }
    public void setSteps(Integer steps) { this.steps = steps; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getSeedsJson() { return seedsJson; }
    public void setSeedsJson(String seedsJson) { this.seedsJson = seedsJson; }
    public String getConstraintsJson() { return constraintsJson; }
    public void setConstraintsJson(String constraintsJson) { this.constraintsJson = constraintsJson; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Long lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}

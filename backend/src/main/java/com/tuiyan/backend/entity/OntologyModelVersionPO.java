package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 模型版本快照元信息 PO，对应 ontology_model_version 表。
 */
@TableName("ontology_model_version")
public class OntologyModelVersionPO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String modelId;
    private Long snapshotAt;
    private String title;
    private String description;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public Long getSnapshotAt() { return snapshotAt; }
    public void setSnapshotAt(Long snapshotAt) { this.snapshotAt = snapshotAt; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}

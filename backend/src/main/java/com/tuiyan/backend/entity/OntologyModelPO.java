package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 本体模型 PO，对应 ontology_model 表。
 */
@TableName("ontology_model")
public class OntologyModelPO {
    // 业务 id（如 om_xxx），由代码生成，不走数据库自增
    @TableId(type = IdType.INPUT)
    private String id;
    private String title;
    private String description;
    private String updatedLabel;
    private Long createdAt;
    private Long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getUpdatedLabel() { return updatedLabel; }
    public void setUpdatedLabel(String updatedLabel) { this.updatedLabel = updatedLabel; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}

package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 本体节点 PO，对应 ontology_node 表。
 * <p>主键为 (model_id, id) 复合主键；MyBatis-Plus 不能直接用 @TableId 表达复合主键，
 * 因此通过 wrapper 显式按这两个字段查询/写入。
 */
@TableName("ontology_node")
public class OntologyNodePO {
    private String id;
    private String modelId;
    private String label;
    private String type;
    private String source;
    private String derivedTablesJson;
    private String derivedSource;
    private String derivedDatabase;
    // 多数据源血缘 JSON：[{source,database,tables[]}]；单值 derivedSource 仅保留首个来源作兼容
    private String derivedSourcesJson;
    private String attributesJson;
    private String constraintsJson;
    private Double x;
    private Double y;
    private Double confidence;
    private String evidence;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getDerivedTablesJson() { return derivedTablesJson; }
    public void setDerivedTablesJson(String derivedTablesJson) { this.derivedTablesJson = derivedTablesJson; }
    public String getDerivedSource() { return derivedSource; }
    public void setDerivedSource(String derivedSource) { this.derivedSource = derivedSource; }
    public String getDerivedDatabase() { return derivedDatabase; }
    public void setDerivedDatabase(String derivedDatabase) { this.derivedDatabase = derivedDatabase; }
    public String getDerivedSourcesJson() { return derivedSourcesJson; }
    public void setDerivedSourcesJson(String derivedSourcesJson) { this.derivedSourcesJson = derivedSourcesJson; }
    public String getAttributesJson() { return attributesJson; }
    public void setAttributesJson(String attributesJson) { this.attributesJson = attributesJson; }
    public String getConstraintsJson() { return constraintsJson; }
    public void setConstraintsJson(String constraintsJson) { this.constraintsJson = constraintsJson; }
    public Double getX() { return x; }
    public void setX(Double x) { this.x = x; }
    public Double getY() { return y; }
    public void setY(Double y) { this.y = y; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
}

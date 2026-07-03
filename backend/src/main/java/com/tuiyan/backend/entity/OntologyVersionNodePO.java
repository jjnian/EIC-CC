package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 版本节点 PO，对应 ontology_version_node 表。
 * <p>结构与 OntologyNodePO 几乎一致，但 owner 是 version_id 而非 model_id。
 */
@TableName("ontology_version_node")
public class OntologyVersionNodePO {
    private Long versionId;
    private String nodeId;
    private String label;
    private String type;
    private String source;
    private String derivedTablesJson;
    private String derivedSource;
    private String derivedDatabase;
    // 多数据源血缘 JSON：[{source,database,tables[]}]；单值 derivedSource 仅保留首个来源作兼容
    private String derivedSourcesJson;
    // 业务领域（经验来源文件夹名）：供前端按域分组/着色/折叠
    private String domain;
    private String attributesJson;
    private String constraintsJson;
    private Double x;
    private Double y;
    private Double confidence;
    private String evidence;

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
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
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
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

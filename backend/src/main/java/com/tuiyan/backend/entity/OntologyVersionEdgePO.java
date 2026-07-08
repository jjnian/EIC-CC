package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 版本边 PO，对应 ontology_version_edge 表。
 */
@TableName("ontology_version_edge")
public class OntologyVersionEdgePO {
    private Long versionId;
    private String edgeId;
    private String fromNodeId;
    private String toNodeId;
    private String label;
    private String source;
    private String derivedTablesJson;
    private String derivedSource;
    private String derivedDatabase;
    // 多数据源血缘 JSON：[{source,database,tables[]}]；单值 derivedSource 仅保留首个来源作兼容
    private String derivedSourcesJson;
    // 业务领域（经验来源文件夹名）：供前端按域分组/着色/折叠
    private String domain;
    private String constraintsJson;
    private Boolean ruleDriven;
    private String ruleId;
    private String relType;
    private String evidence;
    private Double confidence;
    private String evidencesJson;
    private String reviewStatus;
    private String reviewNote;
    private Long reviewedAt;

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getEdgeId() { return edgeId; }
    public void setEdgeId(String edgeId) { this.edgeId = edgeId; }
    public String getFromNodeId() { return fromNodeId; }
    public void setFromNodeId(String fromNodeId) { this.fromNodeId = fromNodeId; }
    public String getToNodeId() { return toNodeId; }
    public void setToNodeId(String toNodeId) { this.toNodeId = toNodeId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
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
    public String getConstraintsJson() { return constraintsJson; }
    public void setConstraintsJson(String constraintsJson) { this.constraintsJson = constraintsJson; }
    public Boolean getRuleDriven() { return ruleDriven; }
    public void setRuleDriven(Boolean ruleDriven) { this.ruleDriven = ruleDriven; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getRelType() { return relType; }
    public void setRelType(String relType) { this.relType = relType; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getEvidencesJson() { return evidencesJson; }
    public void setEvidencesJson(String evidencesJson) { this.evidencesJson = evidencesJson; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public Long getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Long reviewedAt) { this.reviewedAt = reviewedAt; }
}

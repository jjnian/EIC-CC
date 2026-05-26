package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 本体边 PO，对应 ontology_edge 表。
 * <p>主键 (model_id, id) 复合主键。
 */
@TableName("ontology_edge")
public class OntologyEdgePO {
    private String id;
    private String modelId;
    private String fromNodeId;
    private String toNodeId;
    private String label;
    private String source;
    private Boolean ruleDriven;
    private String ruleId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getFromNodeId() { return fromNodeId; }
    public void setFromNodeId(String fromNodeId) { this.fromNodeId = fromNodeId; }
    public String getToNodeId() { return toNodeId; }
    public void setToNodeId(String toNodeId) { this.toNodeId = toNodeId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Boolean getRuleDriven() { return ruleDriven; }
    public void setRuleDriven(Boolean ruleDriven) { this.ruleDriven = ruleDriven; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
}

package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 分支预测边 PO，对应 scenario_edge 表（DAG 增量）。
 */
@TableName("scenario_edge")
public class ScenarioEdgePO {
    private String scenarioId;
    private String edgeId;
    private String fromNodeId;
    private String toNodeId;
    private String label;
    private String source;
    private Boolean ruleDriven;
    private String ruleId;

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }
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
    public Boolean getRuleDriven() { return ruleDriven; }
    public void setRuleDriven(Boolean ruleDriven) { this.ruleDriven = ruleDriven; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
}

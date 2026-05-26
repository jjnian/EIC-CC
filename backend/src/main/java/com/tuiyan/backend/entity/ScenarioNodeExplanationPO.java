package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 节点三段式解释 PO，对应 scenario_node_explanation 表。
 * <p>主键 (scenario_id, node_id)。
 */
@TableName("scenario_node_explanation")
public class ScenarioNodeExplanationPO {
    private String scenarioId;
    private String nodeId;
    private String evidence;
    private String assumptions;
    private String counterexamples;
    private Long generatedAt;
    private String modelName;

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
    public String getAssumptions() { return assumptions; }
    public void setAssumptions(String assumptions) { this.assumptions = assumptions; }
    public String getCounterexamples() { return counterexamples; }
    public void setCounterexamples(String counterexamples) { this.counterexamples = counterexamples; }
    public Long getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Long generatedAt) { this.generatedAt = generatedAt; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
}

package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 分支预测节点 PO，对应 scenario_node 表（DAG 增量）。
 */
@TableName("scenario_node")
public class ScenarioNodePO {
    private String scenarioId;
    private String nodeId;
    private String label;
    private String type;
    private String source;
    private Double x;
    private Double y;
    private Integer predictedStep;
    private Double confidence;
    private Double effectiveProbability;
    private String explanation;

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Double getX() { return x; }
    public void setX(Double x) { this.x = x; }
    public Double getY() { return y; }
    public void setY(Double y) { this.y = y; }
    public Integer getPredictedStep() { return predictedStep; }
    public void setPredictedStep(Integer predictedStep) { this.predictedStep = predictedStep; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public Double getEffectiveProbability() { return effectiveProbability; }
    public void setEffectiveProbability(Double effectiveProbability) { this.effectiveProbability = effectiveProbability; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
}

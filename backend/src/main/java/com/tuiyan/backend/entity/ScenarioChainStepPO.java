package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 推演链步骤 PO，对应 scenario_chain_step 表。
 * <p>triggered_by_json 存上游节点 id 数组的 JSON 序列化。
 */
@TableName("scenario_chain_step")
public class ScenarioChainStepPO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String scenarioId;
    private Integer stepNo;
    private String nodeId;
    private String triggeredByJson;
    private String ruleId;
    private String explanation;
    private Double confidence;
    private Double effectiveProbability;
    private Double cumulativeCredibility;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }
    public Integer getStepNo() { return stepNo; }
    public void setStepNo(Integer stepNo) { this.stepNo = stepNo; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getTriggeredByJson() { return triggeredByJson; }
    public void setTriggeredByJson(String triggeredByJson) { this.triggeredByJson = triggeredByJson; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public Double getEffectiveProbability() { return effectiveProbability; }
    public void setEffectiveProbability(Double effectiveProbability) { this.effectiveProbability = effectiveProbability; }
    public Double getCumulativeCredibility() { return cumulativeCredibility; }
    public void setCumulativeCredibility(Double cumulativeCredibility) { this.cumulativeCredibility = cumulativeCredibility; }
}

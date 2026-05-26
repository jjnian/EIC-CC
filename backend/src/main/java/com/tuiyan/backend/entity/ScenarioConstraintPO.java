package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 分支约束 PO，对应 scenario_constraint 表。
 */
@TableName("scenario_constraint")
public class ScenarioConstraintPO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String scenarioId;
    private String nodeId;
    // force | block | probability
    private String mode;
    private String note;
    private Double probability;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Double getProbability() { return probability; }
    public void setProbability(Double probability) { this.probability = probability; }
}

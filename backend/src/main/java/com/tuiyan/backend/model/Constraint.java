package com.tuiyan.backend.model;

/**
 * What-if 约束：用户在推演前对图中某个节点施加假设。
 *  - force:       该节点必然发生 / 必然存在（提高其下游事件先验）
 *  - block:       该节点不会发生 / 被禁止（任何依赖它的预测应剪枝）
 *  - probability: 给定先验概率（0..1），后端把它作为入口节点的 effProb 初值，并在 prompt 中提示 LLM 做贝叶斯更新
 */
public class Constraint {
    private String nodeId;
    private String mode;        // force | block | probability
    private String note;
    // 仅 mode=probability 时有效，0..1；其它模式为 null
    private Double probability;

    public Constraint() {}
    public Constraint(String nodeId, String mode) {
        this.nodeId = nodeId;
        this.mode = mode;
    }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Double getProbability() { return probability; }
    public void setProbability(Double probability) { this.probability = probability; }
}

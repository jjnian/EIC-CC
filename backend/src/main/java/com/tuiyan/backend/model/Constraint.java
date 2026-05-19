package com.tuiyan.backend.model;

/**
 * What-if 约束：用户在推演前对图中某个节点施加假设。
 *  - force:  该节点必然发生 / 必然存在（提高其下游事件先验）
 *  - block:  该节点不会发生 / 被禁止（任何依赖它的预测应剪枝）
 * v0.7 不引入概率值；如需更细的先验可在 v0.8 扩展 mode=probability + value。
 */
public class Constraint {
    private String nodeId;
    private String mode;     // force | block
    private String note;

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
}

package com.tuiyan.backend.model;

/**
 * P1-7：单个预测节点的"为什么会发生？"解释结果。
 * 三段式结构：依据 / 假设 / 反例。生成一次后缓存到 PredictionDag.explanations。
 */
public class NodeExplanation {
    private String evidence;          // 依据：因果链上有哪些证据支持这一步
    private String assumptions;       // 假设：得出该结论的隐含前提
    private String counterexamples;   // 反例：可能让该步骤不成立的反向证据
    private long generatedAt;         // 生成时间戳（毫秒）
    private String modelName;         // 生成所用模型名（便于审计）

    public NodeExplanation() {}

    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
    public String getAssumptions() { return assumptions; }
    public void setAssumptions(String assumptions) { this.assumptions = assumptions; }
    public String getCounterexamples() { return counterexamples; }
    public void setCounterexamples(String counterexamples) { this.counterexamples = counterexamples; }
    public long getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(long generatedAt) { this.generatedAt = generatedAt; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
}

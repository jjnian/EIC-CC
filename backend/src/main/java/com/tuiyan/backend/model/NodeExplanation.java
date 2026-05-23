package com.tuiyan.backend.model;

/**
 * P1-7：单个预测节点的"为什么会发生？"解释结果。
 * <p>三段式结构：依据 / 假设 / 反例，由 LLM 一次性生成。
 * 生成后缓存到 {@link PredictionDag#getExplanations()}（按 nodeId 索引），
 * 避免用户重复点击"为什么"时反复请求 LLM。
 */
public class NodeExplanation {
    // 依据：因果链上有哪些证据支持这一步
    private String evidence;
    // 假设：得出该结论的隐含前提
    private String assumptions;
    // 反例：可能让该步骤不成立的反向证据
    private String counterexamples;
    // 生成时间戳（毫秒），用于让前端判断缓存新鲜度
    private long generatedAt;
    // 生成所用模型名（便于审计 / 对比不同模型的解释质量）
    private String modelName;

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

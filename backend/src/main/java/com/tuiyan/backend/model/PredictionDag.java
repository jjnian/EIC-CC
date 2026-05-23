package com.tuiyan.backend.model;

import java.util.List;
import java.util.Map;

/**
 * 推演结果的 DAG 形式。chain[] 是 nodes 按预测顺序的线性投影，留给时间线渲染。
 * 仅保存预测增量（delta），加载时与 trunk 合并。
 */
public class PredictionDag {
    private String intent;                          // forward | backward
    private List<Map<String, Object>> nodes;        // 预测节点
    private List<Map<String, Object>> edges;        // 预测边
    private List<Map<String, Object>> chain;        // 预测顺序快照（UI 时间线）
    private List<Constraint> constraints;           // v0.7：本次推演施加的 what-if 约束
    // P1-7：按预测节点 id 缓存的"为什么"解释，按需生成、增量补写
    private Map<String, NodeExplanation> explanations;

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public List<Map<String, Object>> getNodes() { return nodes; }
    public void setNodes(List<Map<String, Object>> nodes) { this.nodes = nodes; }
    public List<Map<String, Object>> getEdges() { return edges; }
    public void setEdges(List<Map<String, Object>> edges) { this.edges = edges; }
    public List<Map<String, Object>> getChain() { return chain; }
    public void setChain(List<Map<String, Object>> chain) { this.chain = chain; }
    public List<Constraint> getConstraints() { return constraints; }
    public void setConstraints(List<Constraint> constraints) { this.constraints = constraints; }
    public Map<String, NodeExplanation> getExplanations() { return explanations; }
    public void setExplanations(Map<String, NodeExplanation> explanations) { this.explanations = explanations; }
}

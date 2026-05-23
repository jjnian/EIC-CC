package com.tuiyan.backend.model;

import java.util.List;
import java.util.Map;

/**
 * 推演结果的 DAG 形式。
 * <p>{@code chain[]} 是 nodes 按预测顺序的线性投影，用于时间线 / 详情面板渲染；
 * nodes/edges 才是真正用来构建画布的 DAG 结构。
 * <p>仅保存预测增量（delta）：加载分支时需要先加载父分支或 trunk，再把本对象的 nodes/edges 合并上去。
 */
public class PredictionDag {
    // forward | backward
    private String intent;
    // 预测节点（仅本分支新增，不含 trunk 节点）
    private List<Map<String, Object>> nodes;
    // 预测边（仅本分支新增）
    private List<Map<String, Object>> edges;
    // 预测顺序快照，UI 时间线按此渲染
    private List<Map<String, Object>> chain;
    // v0.7：本次推演施加的 what-if 约束，复看分支时也能看到当时的假设
    private List<Constraint> constraints;
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

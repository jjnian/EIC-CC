package com.tuiyan.backend.model;

import java.util.List;
import java.util.Map;

public class Scenario {
    private String id;
    private String modelId;
    private String parentBranchId;
    private String name;
    private long createdAt;
    private String intent;                                 // forward | backward
    private List<String> seeds;
    private int steps;
    private String prompt;
    // v0.5 遗留字段：旧分支落盘时保存了完整快照；新分支只填 dag，nodes/edges 留 null
    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
    private List<Map<String, Object>> chain;
    // v0.6 新增：仅保存预测增量
    private PredictionDag dag;
    // P1-8：本次推演发送给 LLM 的完整 prompt 文本快照（system + user 拼接），便于事后审计与复盘
    private String rawPrompt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getParentBranchId() { return parentBranchId; }
    public void setParentBranchId(String parentBranchId) { this.parentBranchId = parentBranchId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public List<String> getSeeds() { return seeds; }
    public void setSeeds(List<String> seeds) { this.seeds = seeds; }
    public int getSteps() { return steps; }
    public void setSteps(int steps) { this.steps = steps; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public List<Map<String, Object>> getNodes() { return nodes; }
    public void setNodes(List<Map<String, Object>> nodes) { this.nodes = nodes; }
    public List<Map<String, Object>> getEdges() { return edges; }
    public void setEdges(List<Map<String, Object>> edges) { this.edges = edges; }
    public List<Map<String, Object>> getChain() { return chain; }
    public void setChain(List<Map<String, Object>> chain) { this.chain = chain; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public PredictionDag getDag() { return dag; }
    public void setDag(PredictionDag dag) { this.dag = dag; }
    public String getRawPrompt() { return rawPrompt; }
    public void setRawPrompt(String rawPrompt) { this.rawPrompt = rawPrompt; }
}

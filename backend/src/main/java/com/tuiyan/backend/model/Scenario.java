package com.tuiyan.backend.model;

import java.util.List;
import java.util.Map;

public class Scenario {
    private String id;
    private String modelId;
    private String parentBranchId;
    private String name;
    private long createdAt;
    private List<String> seeds;
    private int steps;
    private String prompt;
    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
    private List<Map<String, Object>> chain;

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
}

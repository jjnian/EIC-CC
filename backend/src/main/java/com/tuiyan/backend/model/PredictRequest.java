package com.tuiyan.backend.model;

import java.util.List;
import java.util.Map;

public class PredictRequest {
    private String modelId;
    private String parentBranchId;
    private String name;
    private String intent;                  // forward (默认) | backward
    private List<String> seeds;
    private Integer steps;
    private String prompt;
    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
    private String modelOverride;
    private String configId;

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getParentBranchId() { return parentBranchId; }
    public void setParentBranchId(String parentBranchId) { this.parentBranchId = parentBranchId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<String> getSeeds() { return seeds; }
    public void setSeeds(List<String> seeds) { this.seeds = seeds; }
    public Integer getSteps() { return steps; }
    public void setSteps(Integer steps) { this.steps = steps; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public List<Map<String, Object>> getNodes() { return nodes; }
    public void setNodes(List<Map<String, Object>> nodes) { this.nodes = nodes; }
    public List<Map<String, Object>> getEdges() { return edges; }
    public void setEdges(List<Map<String, Object>> edges) { this.edges = edges; }
    public String getModelOverride() { return modelOverride; }
    public void setModelOverride(String modelOverride) { this.modelOverride = modelOverride; }
    public String getConfigId() { return configId; }
    public void setConfigId(String configId) { this.configId = configId; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
}

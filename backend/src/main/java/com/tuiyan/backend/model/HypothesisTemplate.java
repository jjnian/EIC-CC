package com.tuiyan.backend.model;

import java.util.List;

public class HypothesisTemplate {
    private String id;
    private String modelId;
    private String name;
    private List<String> seeds;
    private int steps;
    private String intent;
    private List<Constraint> constraints;
    private String prompt;
    private long createdAt;
    private long lastUsedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<String> getSeeds() { return seeds; }
    public void setSeeds(List<String> seeds) { this.seeds = seeds; }
    public int getSteps() { return steps; }
    public void setSteps(int steps) { this.steps = steps; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public List<Constraint> getConstraints() { return constraints; }
    public void setConstraints(List<Constraint> constraints) { this.constraints = constraints; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(long lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}

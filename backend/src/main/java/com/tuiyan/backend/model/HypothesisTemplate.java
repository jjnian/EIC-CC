package com.tuiyan.backend.model;

import java.util.List;

/**
 * 推演假设模板：用户保存的常用推演参数预设。
 * <p>下次发起推演时可一键填入 seeds、steps、intent、constraints、prompt 等，避免重复输入。
 * 持久化到 {@code ~/.tuiyan/hypothesis-templates/<id>.json}，列表按 lastUsedAt 排序。
 */
public class HypothesisTemplate {
    private String id;
    // 该模板默认绑定的本体图谱模型 id（不同图谱节点 id 不通用）
    private String modelId;
    private String name;
    private List<String> seeds;
    private int steps;
    // forward | backward
    private String intent;
    private List<Constraint> constraints;
    private String prompt;
    private long createdAt;
    // 最近一次被使用的时间戳，用于"最常用模板"排序
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

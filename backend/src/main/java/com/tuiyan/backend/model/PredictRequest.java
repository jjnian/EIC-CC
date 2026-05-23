package com.tuiyan.backend.model;

import java.util.List;
import java.util.Map;

/**
 * 推演请求 DTO（前端 → 后端）。
 * <p>由 {@code POST /api/scenarios/predict} 接收，是 {@link com.tuiyan.backend.service.PredictionOrchestrator}
 * 的输入契约。携带：
 * <ul>
 *   <li>选用模型（modelId / modelOverride / configId 任选其一生效）；</li>
 *   <li>分支信息（parentBranchId 非空 = 从某分支 fork 而来）；</li>
 *   <li>推演参数（intent / seeds / steps / prompt / constraints）；</li>
 *   <li>当前画布快照（nodes / edges）：让 LLM 看到上下文，同时作为分支 base。</li>
 * </ul>
 */
public class PredictRequest {
    private String modelId;
    // 父分支 id：null 或空表示从 trunk 推演；非空表示从已有分支再次 fork
    private String parentBranchId;
    private String name;
    // forward (默认) | backward
    private String intent;
    // 种子节点 id 列表，推演由它们出发
    private List<String> seeds;
    // 推演步数；null 时由 LLM 自由决定（但有最大上限）
    private Integer steps;
    private String prompt;
    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
    // 运行时模型名覆盖
    private String modelOverride;
    private String configId;
    // What-if 约束（force / block / probability）
    private List<Constraint> constraints;

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
    public List<Constraint> getConstraints() { return constraints; }
    public void setConstraints(List<Constraint> constraints) { this.constraints = constraints; }
}

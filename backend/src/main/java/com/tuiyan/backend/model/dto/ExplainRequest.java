package com.tuiyan.backend.model.dto;

/**
 * P1-7：解释请求体。
 * <p>原来作为 ScenarioController.ExplainRequest 内部类，抽出来便于复用与单测。
 */
public class ExplainRequest {
    private String nodeId;
    private String modelOverride;
    private String configId;
    private boolean forceRegenerate;

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getModelOverride() { return modelOverride; }
    public void setModelOverride(String modelOverride) { this.modelOverride = modelOverride; }
    public String getConfigId() { return configId; }
    public void setConfigId(String configId) { this.configId = configId; }
    public boolean isForceRegenerate() { return forceRegenerate; }
    public void setForceRegenerate(boolean forceRegenerate) { this.forceRegenerate = forceRegenerate; }
}

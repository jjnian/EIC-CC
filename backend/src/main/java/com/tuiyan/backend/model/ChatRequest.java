package com.tuiyan.backend.model;

import java.util.List;
import java.util.Map;

public class ChatRequest {
    private String message;
    private List<Map<String, Object>> history;
    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
    private List<String> attachments;

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<Map<String, Object>> getHistory() { return history; }
    public void setHistory(List<Map<String, Object>> history) { this.history = history; }
    public List<Map<String, Object>> getNodes() { return nodes; }
    public void setNodes(List<Map<String, Object>> nodes) { this.nodes = nodes; }
    public List<Map<String, Object>> getEdges() { return edges; }
    public void setEdges(List<Map<String, Object>> edges) { this.edges = edges; }
    public List<String> getAttachments() { return attachments; }
    public void setAttachments(List<String> attachments) { this.attachments = attachments; }
    public String getModelOverride() { return modelOverride; }
    public void setModelOverride(String modelOverride) { this.modelOverride = modelOverride; }
    public String getConfigId() { return configId; }
    public void setConfigId(String configId) { this.configId = configId; }
    private String modelOverride;
    private String configId;
}

package com.tuiyan.backend.model;

import java.util.List;
import java.util.Map;

public class OntologyModel {
    private String id;
    private String title;
    private String desc;
    private String updated;
    private long createdAt;
    private long updatedAt;
    private GraphData graphData;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDesc() { return desc; }
    public void setDesc(String desc) { this.desc = desc; }
    public String getUpdated() { return updated; }
    public void setUpdated(String updated) { this.updated = updated; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public GraphData getGraphData() { return graphData; }
    public void setGraphData(GraphData graphData) { this.graphData = graphData; }

    public static class GraphData {
        private List<Map<String, Object>> nodes;
        private List<Map<String, Object>> edges;
        public List<Map<String, Object>> getNodes() { return nodes; }
        public void setNodes(List<Map<String, Object>> nodes) { this.nodes = nodes; }
        public List<Map<String, Object>> getEdges() { return edges; }
        public void setEdges(List<Map<String, Object>> edges) { this.edges = edges; }
    }
}

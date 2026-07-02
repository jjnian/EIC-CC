package com.tuiyan.backend.model;

import java.util.List;
import java.util.Map;

/**
 * 本体图谱模型：项目的基础数据结构，描述实体类型与因果关系骨架。
 * <p>每个模型独立保存为 {@code ~/.tuiyan/ontology-models/<id>.json}，
 * 用户可以建多个图谱（如"金融风险"、"供应链"）。
 * <p>{@code updated}（字符串显示用）与 {@code updatedAt}（毫秒时间戳，用于排序）并存
 * 是历史遗留：早期版本只用 updated，后来加 updatedAt 但旧文件可能没有。
 */
public class OntologyModel {
    private String id;
    private String title;
    private String desc;
    // 人类可读的更新时间字符串（如 "刚刚"、"3 分钟前"），历史遗留
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

    /**
     * 图谱内容：节点 + 边。
     * 使用 {@code Map<String,Object>} 而非强类型 POJO，是为了在不发版的前提下扩展节点属性
     * （例如新增 confidence、explanation 字段时无需改后端）。
     */
    public static class GraphData {
        private List<Map<String, Object>> nodes;
        private List<Map<String, Object>> edges;
        public List<Map<String, Object>> getNodes() { return nodes; }
        public void setNodes(List<Map<String, Object>> nodes) { this.nodes = nodes; }
        public List<Map<String, Object>> getEdges() { return edges; }
        public void setEdges(List<Map<String, Object>> edges) { this.edges = edges; }
    }
}

package com.tuiyan.backend.model;

import java.util.List;
import java.util.Map;

/**
 * 聊天请求 DTO（前端 → 后端）。
 * <p>由 {@code /api/chat} 接收。携带当前对话上下文（消息、历史、图谱快照、附件）
 * 与模型选择信息（id 或运行时覆写），交由 {@link com.tuiyan.backend.service.ChatLlmService} 处理。
 * <p>本类仅是数据容器，不对字段做校验，业务规则在 service 中实现。
 */
public class ChatRequest {
    // 用户本次输入文本
    private String message;
    // 之前的对话历史（按时间顺序，含 role / content）；前端持有，每次完整回传
    private List<Map<String, Object>> history;
    // 当前图谱节点快照，用于让 LLM 看到上下文
    private List<Map<String, Object>> nodes;
    // 当前图谱边快照
    private List<Map<String, Object>> edges;
    // 附件列表（图片 base64 / 文档抽取后的文本等）
    private List<Map<String, Object>> attachments;

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<Map<String, Object>> getHistory() { return history; }
    public void setHistory(List<Map<String, Object>> history) { this.history = history; }
    public List<Map<String, Object>> getNodes() { return nodes; }
    public void setNodes(List<Map<String, Object>> nodes) { this.nodes = nodes; }
    public List<Map<String, Object>> getEdges() { return edges; }
    public void setEdges(List<Map<String, Object>> edges) { this.edges = edges; }
    public List<Map<String, Object>> getAttachments() { return attachments; }
    public void setAttachments(List<Map<String, Object>> attachments) { this.attachments = attachments; }
    public String getModelOverride() { return modelOverride; }
    public void setModelOverride(String modelOverride) { this.modelOverride = modelOverride; }
    public String getConfigId() { return configId; }
    public void setConfigId(String configId) { this.configId = configId; }

    // 运行时模型名覆盖（如 "gpt-4o" 临时换成 "gpt-4.1"），不改变 configId 关联的配置
    private String modelOverride;
    // 选用的模型配置 id，对应 LlmProperties.models[].id
    private String configId;
}

package com.tuiyan.backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一段对话会话（左侧对话列表的一项）。
 * <p>每个 Conversation 是独立的 JSON 文件，落盘到 {@code ~/.tuiyan/conversations/<id>.json}。
 * msgs 用 {@code Map<String,Object>} 保持灵活：可容纳工具调用等非典型 role/content 形式。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Conversation {
    private String id;
    // 会话标题，常由首条消息内容自动生成
    private String title;
    // 绑定的本体血缘图 ID：一个会话对应一张图，会话内所有改动都落到这张图上。
    // 由「新对话」首次发言惰性建图后写入；历史会话可能为 null（回看时退化为扫描消息里的 graphModelId）。
    private String modelId;
    private long createdAt;
    // updatedAt 用于会话列表按时间排序
    private long updatedAt;
    // 消息列表，按时间顺序追加；每条 msg 自定义形状，最常见字段为 role / content / timestamp
    private List<Map<String, Object>> msgs = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public List<Map<String, Object>> getMsgs() { return msgs; }
    public void setMsgs(List<Map<String, Object>> msgs) { this.msgs = msgs; }
}

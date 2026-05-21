package com.tuiyan.backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Conversation {
    private String id;
    private String title;
    private long createdAt;
    private long updatedAt;
    private List<Map<String, Object>> msgs = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public List<Map<String, Object>> getMsgs() { return msgs; }
    public void setMsgs(List<Map<String, Object>> msgs) { this.msgs = msgs; }
}

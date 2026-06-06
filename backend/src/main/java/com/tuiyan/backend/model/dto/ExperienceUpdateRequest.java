package com.tuiyan.backend.model.dto;

/** 编辑经验请求：title / content / tags 均可选，null 表示不改动该字段。 */
public class ExperienceUpdateRequest {
    private String title;
    private String content;
    private String tags;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
}

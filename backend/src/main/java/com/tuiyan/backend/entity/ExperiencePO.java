package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 经验库 PO，对应 experience 表。
 * <p>一条经验 = 一篇经验文档（标题 + 正文 + 标签），按工作空间隔离，
 * 在侧栏与「数据源 / 历史记录」同级别展示。
 */
@TableName("experience")
public class ExperiencePO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String title;
    /** 经验正文（文本 / Markdown） */
    private String content;
    /** 逗号分隔的标签 */
    private String tags;
    private Long createdAt;
    private Long updatedAt;
    /** 向量索引状态：none | indexing | indexed | error */
    private String indexStatus;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String indexStatus) { this.indexStatus = indexStatus; }
}

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
    /** 来源：manual（手写）| upload（上传文件）| ddl（数据源结构导出供血） */
    private String origin;
    /** 上传文件的原始文件名（origin=upload 时有值） */
    private String fileName;
    /** 上传文件的 MIME 类型 */
    private String fileMime;
    /** 上传文件的字节大小 */
    private Long fileSize;
    /** 原始文件在对象存储中的 key（origin=upload 且归档成功时有值，供预览/下载） */
    private String storagePath;

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
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileMime() { return fileMime; }
    public void setFileMime(String fileMime) { this.fileMime = fileMime; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
}

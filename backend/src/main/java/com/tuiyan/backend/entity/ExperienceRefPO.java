package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 经验库引用 PO，对应 experience_ref 表。
 * <p>经验库为全局公共资源：新增只进公共库，不自动归属工作空间。工作空间通过「引用」把某条经验
 * 纳入自己的侧栏树，一行 = 一个工作空间引用了一条经验。{@code folderId} 是该引用在「引用所在
 * 工作空间」内的归类文件夹（NULL=根），各工作空间各自归类、互不影响。
 */
@TableName("experience_ref")
public class ExperienceRefPO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String experienceId;
    /** 该引用在所在工作空间内的归类文件夹 id；NULL = 工作空间根目录 */
    private String folderId;
    private Long createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getExperienceId() { return experienceId; }
    public void setExperienceId(String experienceId) { this.experienceId = experienceId; }
    public String getFolderId() { return folderId; }
    public void setFolderId(String folderId) { this.folderId = folderId; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}

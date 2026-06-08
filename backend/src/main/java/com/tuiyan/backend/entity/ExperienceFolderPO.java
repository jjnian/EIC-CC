package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 经验库文件夹 PO，对应 experience_folder 表。
 * <p>工作空间内任意层级：{@code parentId} 自引用，NULL 表示工作空间根目录。
 * 用于把经验/上传文件归类、分门别类展示在侧栏的经验库树里（与数据源文件夹结构平行）。
 */
@TableName("experience_folder")
public class ExperienceFolderPO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    /** 父文件夹 id；NULL = 工作空间根目录 */
    private String parentId;
    private String name;
    private Integer sortOrder;
    private Long createdAt;
    private Long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}

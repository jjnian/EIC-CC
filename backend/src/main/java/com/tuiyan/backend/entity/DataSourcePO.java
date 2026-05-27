package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 数据源 PO，对应 data_source 表。
 * <p>每次 /api/ontology-models/extract 成功后写一行，按工作空间隔离展示在侧栏树。
 */
@TableName("data_source")
public class DataSourcePO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    /** file | url */
    private String kind;
    private String name;
    private String mime;
    private Long sizeBytes;
    /** pages / chars / title / truncated 等附加信息序列化 */
    private String extraJson;
    private Long createdAt;
    /** 类型特定连接配置 JSON：mysql/pgsql/file_stored/https_api 各异；旧 kind 为 null */
    private String configJson;
    /** idle | connected | error */
    private String status;
    /** 上次连接测试时间戳（毫秒） */
    private Long lastTestedAt;
    /** 上次失败原因（用于前端 hover 展示） */
    private String lastError;
    /** 配置最近修改时间（毫秒） */
    private Long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMime() { return mime; }
    public void setMime(String mime) { this.mime = mime; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getExtraJson() { return extraJson; }
    public void setExtraJson(String extraJson) { this.extraJson = extraJson; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(Long lastTestedAt) { this.lastTestedAt = lastTestedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}

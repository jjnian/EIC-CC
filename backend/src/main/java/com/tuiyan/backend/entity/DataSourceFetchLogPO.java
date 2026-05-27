package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 数据源执行历史（仅 https_api 写入）。每次手动执行 / 定时拉取都追加一行。
 */
@TableName("data_source_fetch_log")
public class DataSourceFetchLogPO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String dataSourceId;
    private Long fetchedAt;
    private Integer statusCode;
    private Boolean success;
    private String responseBody;
    private String errorMsg;
    private Integer durationMs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(String dataSourceId) { this.dataSourceId = dataSourceId; }
    public Long getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Long fetchedAt) { this.fetchedAt = fetchedAt; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
}

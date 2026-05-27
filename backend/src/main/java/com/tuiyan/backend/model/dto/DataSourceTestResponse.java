package com.tuiyan.backend.model.dto;

/** 数据源连接测试结果：success + 消息（错误时填错误信息）+ 延迟。 */
public class DataSourceTestResponse {
    private boolean success;
    private String message;
    private Integer latencyMs;

    public DataSourceTestResponse() {}
    public DataSourceTestResponse(boolean success, String message, Integer latencyMs) {
        this.success = success; this.message = message; this.latencyMs = latencyMs;
    }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
}

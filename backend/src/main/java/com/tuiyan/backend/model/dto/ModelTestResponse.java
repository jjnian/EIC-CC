package com.tuiyan.backend.model.dto;

/**
 * 模型连通性测试响应。
 * <p>status: "ok" 携带 latencyMs；"error" 携带 error 文本。
 * 调用方根据 status 字段区分成功 / 失败。
 */
public record ModelTestResponse(String status, Long latencyMs, String error) {

    public static ModelTestResponse ok(long latencyMs) {
        return new ModelTestResponse("ok", latencyMs, null);
    }

    public static ModelTestResponse error(String message) {
        return new ModelTestResponse("error", null, message);
    }
}

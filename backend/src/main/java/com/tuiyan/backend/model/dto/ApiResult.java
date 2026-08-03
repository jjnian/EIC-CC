package com.tuiyan.backend.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一 API 响应封装，所有 Controller 的同步返回值均使用此类。
 * <p>成功时使用 {@link #ok(Object)} 或 {@link #ok()}；
 * 失败时由 {@link com.tuiyan.backend.config.GlobalExceptionHandler} 统一构造 {@link #fail(Integer, String)}。
 * <p>SSE 流式端点（SseEmitter）和文件流端点（InputStreamResource）不使用此封装。
 *
 * @param <T> 响应数据的具体类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResult<T> {

    private Integer code;
    private String message;
    private T data;

    private ApiResult() {}

    /** 成功响应（带数据） */
    public static <T> ApiResult<T> ok(T data) {
        ApiResult<T> r = new ApiResult<>();
        r.code = 200;
        r.message = "成功";
        r.data = data;
        return r;
    }

    /** 成功响应（无数据，如删除/清空操作） */
    public static <T> ApiResult<T> ok() {
        return ok(null);
    }

    /** 失败响应 */
    public static <T> ApiResult<T> fail(Integer code, String message) {
        ApiResult<T> r = new ApiResult<>();
        r.code = code;
        r.message = message;
        return r;
    }

    // ===== getters =====

    public Integer getCode() { return code; }

    public String getMessage() { return message; }

    public T getData() { return data; }
}

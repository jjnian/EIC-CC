package com.tuiyan.backend.exception;

/**
 * 资源未找到 → 由 {@link com.tuiyan.backend.config.GlobalExceptionHandler} 映射到 HTTP 404。
 * <p>用于在 service 中区分"参数校验失败"（{@link IllegalArgumentException} → 400）
 * 与"目标资源不存在"两种语义，避免前端误把缺资源当成参数错误。
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}

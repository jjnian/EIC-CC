package com.tuiyan.backend.config;

/**
 * 资源未找到 → HTTP 404。
 * 用于区分参数校验失败(IllegalArgumentException → 400)与目标资源不存在两种语义。
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}

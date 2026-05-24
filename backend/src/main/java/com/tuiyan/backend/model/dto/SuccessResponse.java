package com.tuiyan.backend.model.dto;

/** 通用 success-only 响应；替代 {@code Map.of("success", ok)}。 */
public record SuccessResponse(boolean success) {
    public static final SuccessResponse OK = new SuccessResponse(true);
}

package com.tuiyan.backend.model.dto;

/**
 * 通用"删除 / 批量操作"成功响应：success + 实际处理数量。
 * <p>替代 Controller 里散乱的 {@code Map.of("success", ..., "count", ...)} 拼装。
 */
public record SuccessCountResponse(boolean success, int count) {
    public static SuccessCountResponse of(int count) {
        return new SuccessCountResponse(count > 0, count);
    }
}

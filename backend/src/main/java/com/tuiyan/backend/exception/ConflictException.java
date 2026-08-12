package com.tuiyan.backend.exception;

/**
 * 并发修改冲突 → 由 {@link com.tuiyan.backend.config.GlobalExceptionHandler} 映射到 HTTP 409。
 * <p>用于乐观锁校验失败（如本体模型保存时基线 updatedAt 已过期），
 * 提示调用方刷新最新数据后重试，而不是无声地 last-write-wins 覆盖。
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}

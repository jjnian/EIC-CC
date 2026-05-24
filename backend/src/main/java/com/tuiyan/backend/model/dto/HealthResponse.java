package com.tuiyan.backend.model.dto;

/**
 * 健康检查响应：进程内存、数据目录可用性、运行时长。
 * <p>由 {@code /api/system/health} 返回。
 */
public record HealthResponse(String status,
                             boolean dataDir,
                             long freeMemoryMb,
                             long totalMemoryMb,
                             long uptime) {}

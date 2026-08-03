package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.dto.ApiResult;
import com.tuiyan.backend.model.dto.HealthResponse;
import com.tuiyan.backend.service.LlmMetricsService;
import com.tuiyan.backend.service.SystemHealthService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 系统健康检查和 LLM 调用统计端点。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final SystemHealthService healthService;
    private final LlmMetricsService metricsService;

    public SystemController(SystemHealthService healthService, LlmMetricsService metricsService) {
        this.healthService = healthService;
        this.metricsService = metricsService;
    }

    @GetMapping("/health")
    public ApiResult<HealthResponse> health() {
        return ApiResult.ok(healthService.getHealth());
    }

    @GetMapping("/metrics/llm")
    public ApiResult<Map<String, Object>> llmMetrics() {
        return ApiResult.ok(metricsService.getStats());
    }

    @PostMapping("/metrics/llm/reset")
    public ApiResult<Void> resetMetrics() {
        metricsService.reset();
        return ApiResult.ok();
    }
}

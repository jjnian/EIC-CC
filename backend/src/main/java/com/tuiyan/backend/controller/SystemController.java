package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.dto.HealthResponse;
import com.tuiyan.backend.service.LlmMetricsService;
import com.tuiyan.backend.service.SystemHealthService;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(healthService.getHealth());
    }

    @GetMapping("/metrics/llm")
    public ResponseEntity<Map<String, Object>> llmMetrics() {
        return ResponseEntity.ok(metricsService.getStats());
    }

    @PostMapping("/metrics/llm/reset")
    public ResponseEntity<Void> resetMetrics() {
        metricsService.reset();
        return ResponseEntity.noContent().build();
    }
}

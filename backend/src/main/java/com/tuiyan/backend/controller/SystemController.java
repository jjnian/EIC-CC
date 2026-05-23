package com.tuiyan.backend.controller;

import com.tuiyan.backend.service.LlmMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Map;

/**
 * 系统健康检查和 LLM 调用统计端点
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final LlmMetricsService metricsService;

    public SystemController(LlmMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /** 健康检查：返回系统运行状态、内存、数据目录可用性 */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        String dataDir = System.getProperty("user.home") + File.separator + ".tuiyan";
        boolean dataDirOk = new File(dataDir).exists() && new File(dataDir).canWrite();
        long freeMemMb = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long totalMemMb = Runtime.getRuntime().totalMemory() / (1024 * 1024);

        return ResponseEntity.ok(Map.of(
            "status", dataDirOk ? "UP" : "DEGRADED",
            "dataDir", dataDirOk,
            "freeMemoryMb", freeMemMb,
            "totalMemoryMb", totalMemMb,
            "uptime", ManagementFactory.getRuntimeMXBean().getUptime()
        ));
    }

    /** 获取 LLM 调用统计 */
    @GetMapping("/metrics/llm")
    public ResponseEntity<Map<String, Object>> llmMetrics() {
        return ResponseEntity.ok(metricsService.getStats());
    }

    /** 重置 LLM 调用统计 */
    @PostMapping("/metrics/llm/reset")
    public ResponseEntity<Void> resetMetrics() {
        metricsService.reset();
        return ResponseEntity.noContent().build();
    }
}

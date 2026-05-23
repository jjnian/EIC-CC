package com.tuiyan.backend.controller;

import com.tuiyan.backend.config.LlmProperties;
import com.tuiyan.backend.service.LlmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final LlmService llmService;

    public ModelController(LlmService llmService) {
        this.llmService = llmService;
    }

    @GetMapping
    public ResponseEntity<List<LlmProperties.ModelEntry>> getAllModels() {
        return ResponseEntity.ok(llmService.getAllModelConfigs());
    }

    /** 测试指定模型的连通性，返回延迟(ms)或错误信息 */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testModel(@PathVariable String id) {
        try {
            long latencyMs = llmService.testModelConnection(id);
            return ResponseEntity.ok(Map.of(
                "status", "ok",
                "latencyMs", latencyMs
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "error", e.getMessage()
            ));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ResponseEntity.ok(Map.of(
                "status", "error",
                "error", msg
            ));
        }
    }
}

package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.ModelConfig;
import com.tuiyan.backend.service.LlmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
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
    public ResponseEntity<?> getAllModels() {
        try {
            List<ModelConfig> configs = llmService.getAllModelConfigs();
            return ResponseEntity.ok(configs);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> createModel(@RequestBody ModelConfigRequest request) {
        try {
            if (request.getName() == null || request.getName().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "名称不能为空"));
            }
            if (request.getBaseUrl() == null || request.getBaseUrl().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Base URL 不能为空"));
            }
            if (request.getModelName() == null || request.getModelName().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "模型名称不能为空"));
            }
            ModelConfig config = llmService.createModelConfig(request);
            return ResponseEntity.ok(config);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateModel(@PathVariable String id, @RequestBody ModelConfigRequest request) {
        try {
            if (request.getName() == null || request.getName().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "名称不能为空"));
            }
            if (request.getBaseUrl() == null || request.getBaseUrl().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Base URL 不能为空"));
            }
            if (request.getModelName() == null || request.getModelName().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "模型名称不能为空"));
            }
            ModelConfig config = llmService.updateModelConfig(id, request);
            return ResponseEntity.ok(config);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteModel(@PathVariable String id) {
        try {
            llmService.deleteModelConfig(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<?> toggleModel(@PathVariable String id) {
        try {
            llmService.toggleModelConfig(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    public static class ModelConfigRequest {
        private String name;
        private String baseUrl;
        private String modelName;
        private String apiKey;
        private String provider;
        private String description;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private List<String> capabilities;
        private String protocol;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Integer getContextWindow() { return contextWindow; }
        public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }
        public Integer getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
        public List<String> getCapabilities() { return capabilities; }
        public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
    }
}

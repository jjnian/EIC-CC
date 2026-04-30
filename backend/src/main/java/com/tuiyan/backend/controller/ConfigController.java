package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.ConfigRequest;
import com.tuiyan.backend.model.ConfigResponse;
import com.tuiyan.backend.service.LlmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final LlmService llmService;

    public ConfigController(LlmService llmService) {
        this.llmService = llmService;
    }

    @GetMapping
    public ResponseEntity<?> getConfig() {
        try {
            ConfigResponse config = llmService.getConfigResponse();
            return ResponseEntity.ok(config);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> saveConfig(@RequestBody ConfigRequest request) {
        try {
            llmService.saveConfig(request.getProvider(), request.getBaseUrl(), request.getModelName(), request.getApiKey());
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}

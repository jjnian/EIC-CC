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
    public ResponseEntity<ConfigResponse> getConfig() throws IOException {
        return ResponseEntity.ok(llmService.getConfigResponse());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody ConfigRequest request) throws IOException {
        llmService.saveConfig(request.getProvider(), request.getBaseUrl(), request.getModelName(), request.getApiKey());
        return ResponseEntity.ok(Map.of("success", true));
    }
}

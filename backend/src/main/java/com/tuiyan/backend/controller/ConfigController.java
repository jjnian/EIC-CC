package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.ConfigResponse;
import com.tuiyan.backend.service.LlmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final LlmService llmService;

    public ConfigController(LlmService llmService) {
        this.llmService = llmService;
    }

    @GetMapping
    public ResponseEntity<ConfigResponse> getConfig() {
        return ResponseEntity.ok(llmService.getConfigResponse());
    }
}

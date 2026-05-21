package com.tuiyan.backend.controller;

import com.tuiyan.backend.config.LlmProperties;
import com.tuiyan.backend.service.LlmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
}

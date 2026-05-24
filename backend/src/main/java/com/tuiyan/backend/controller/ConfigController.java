package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.ConfigResponse;
import com.tuiyan.backend.service.ModelInfoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ModelInfoService modelInfoService;

    public ConfigController(ModelInfoService modelInfoService) {
        this.modelInfoService = modelInfoService;
    }

    @GetMapping
    public ResponseEntity<ConfigResponse> getConfig() {
        return ResponseEntity.ok(modelInfoService.getConfigResponse());
    }
}

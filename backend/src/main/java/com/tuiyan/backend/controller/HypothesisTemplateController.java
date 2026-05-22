package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.HypothesisTemplate;
import com.tuiyan.backend.service.HypothesisTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/hypothesis-templates")
public class HypothesisTemplateController {

    private final HypothesisTemplateService service;

    public HypothesisTemplateController(HypothesisTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String modelId) {
        return ResponseEntity.ok(service.listByModel(modelId));
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody HypothesisTemplate template) throws IOException {
        if (template.getId() == null || template.getId().isBlank()) {
            template.setId("ht_" + System.currentTimeMillis());
        }
        if (template.getCreatedAt() == 0) {
            template.setCreatedAt(System.currentTimeMillis());
        }
        template.setLastUsedAt(System.currentTimeMillis());
        service.save(template);
        return ResponseEntity.ok(template);
    }

    @PostMapping("/{id}/touch")
    public ResponseEntity<?> touch(@PathVariable String id) throws IOException {
        service.touch(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        boolean ok = service.delete(id);
        return ResponseEntity.ok(Map.of("success", ok));
    }
}

package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.HypothesisTemplate;
import com.tuiyan.backend.model.dto.SuccessResponse;
import com.tuiyan.backend.service.HypothesisTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/hypothesis-templates")
public class HypothesisTemplateController {

    private final HypothesisTemplateService service;

    public HypothesisTemplateController(HypothesisTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<HypothesisTemplate>> list(@RequestParam(required = false) String modelId) {
        return ResponseEntity.ok(service.listByModel(modelId));
    }

    @PostMapping
    public ResponseEntity<HypothesisTemplate> save(@RequestBody HypothesisTemplate template) throws IOException {
        return ResponseEntity.ok(service.save(template));
    }

    @PostMapping("/{id}/touch")
    public ResponseEntity<SuccessResponse> touch(@PathVariable String id) throws IOException {
        service.touch(id);
        return ResponseEntity.ok(SuccessResponse.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessResponse> delete(@PathVariable String id) {
        return ResponseEntity.ok(new SuccessResponse(service.delete(id)));
    }
}

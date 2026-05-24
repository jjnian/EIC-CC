package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.service.DocumentExtractionService;
import com.tuiyan.backend.service.OntologyModelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology-models")
public class OntologyModelController {

    private final OntologyModelService svc;
    private final DocumentExtractionService extractionService;

    public OntologyModelController(OntologyModelService svc, DocumentExtractionService extractionService) {
        this.svc = svc;
        this.extractionService = extractionService;
    }

    @GetMapping
    public ResponseEntity<?> list() throws IOException {
        return ResponseEntity.ok(svc.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) throws IOException {
        OntologyModel m = svc.get(id);
        return m == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(m);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody OntologyModel m) throws IOException {
        return ResponseEntity.ok(svc.save(m));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody OntologyModel m) throws IOException {
        m.setId(id);
        return ResponseEntity.ok(svc.save(m));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        boolean ok = svc.delete(id);
        return ResponseEntity.ok(Map.of("success", ok, "count", ok ? 1 : 0));
    }

    // 获取指定模型的版本快照列表
    @GetMapping("/{id}/versions")
    public ResponseEntity<List<Map<String, Object>>> listVersions(@PathVariable String id) {
        return ResponseEntity.ok(svc.listVersions(id));
    }

    // 恢复指定时间戳的版本快照
    @PostMapping("/{id}/versions/{timestamp}/restore")
    public ResponseEntity<OntologyModel> restoreVersion(@PathVariable String id, @PathVariable long timestamp) throws IOException {
        return ResponseEntity.ok(svc.restoreVersion(id, timestamp));
    }

    @PostMapping(value = "/extract", consumes = {"multipart/form-data"})
    public ResponseEntity<?> extract(
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "urls", required = false) List<String> urls,
            @RequestParam(value = "modelOverride", required = false) String modelOverride,
            @RequestParam(value = "configId", required = false) String configId) throws Exception {
        return ResponseEntity.ok(extractionService.extract(
                files == null ? List.of() : files,
                urls == null ? List.of() : urls,
                modelOverride, configId));
    }
}

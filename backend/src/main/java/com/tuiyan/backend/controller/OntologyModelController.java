package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.service.OntologyModelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology-models")
public class OntologyModelController {

    private final OntologyModelService svc;

    public OntologyModelController(OntologyModelService svc) {
        this.svc = svc;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        try { return ResponseEntity.ok(svc.list()); }
        catch (IOException e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        try {
            OntologyModel m = svc.get(id);
            return m == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(m);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody OntologyModel m) {
        try { return ResponseEntity.ok(svc.save(m)); }
        catch (IOException e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody OntologyModel m) {
        try {
            m.setId(id);
            return ResponseEntity.ok(svc.save(m));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("deleted", svc.delete(id)));
    }
}

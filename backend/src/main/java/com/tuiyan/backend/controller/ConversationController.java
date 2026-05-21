package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.Conversation;
import com.tuiyan.backend.service.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService svc;

    public ConversationController(ConversationService svc) {
        this.svc = svc;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(svc.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) throws IOException {
        Conversation c = svc.get(id);
        return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(c);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Conversation c) throws IOException {
        return ResponseEntity.ok(svc.save(c));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Conversation c) throws IOException {
        c.setId(id);
        return ResponseEntity.ok(svc.save(c));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        boolean ok = svc.delete(id);
        return ResponseEntity.ok(Map.of("success", ok));
    }
}

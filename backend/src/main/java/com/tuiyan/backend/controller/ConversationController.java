package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.Conversation;
import com.tuiyan.backend.model.dto.SuccessResponse;
import com.tuiyan.backend.service.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService svc;

    public ConversationController(ConversationService svc) {
        this.svc = svc;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String workspaceId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            return ResponseEntity.ok(svc.list(workspaceId));
        }
        return ResponseEntity.ok(svc.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Conversation> get(@PathVariable String id) throws IOException {
        Conversation c = svc.get(id);
        return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(c);
    }

    @PostMapping
    public ResponseEntity<Conversation> create(@RequestBody Conversation c) throws IOException {
        return ResponseEntity.ok(svc.save(c));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Conversation> update(@PathVariable String id, @RequestBody Conversation c) throws IOException {
        return ResponseEntity.ok(svc.update(id, c));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessResponse> delete(@PathVariable String id) {
        return ResponseEntity.ok(new SuccessResponse(svc.delete(id)));
    }
}

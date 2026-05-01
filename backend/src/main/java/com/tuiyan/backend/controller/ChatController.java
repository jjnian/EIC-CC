package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuiyan.backend.model.ChatRequest;
import com.tuiyan.backend.service.LlmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final LlmService llmService;

    public ChatController(LlmService llmService) {
        this.llmService = llmService;
    }

    @PostMapping
    public Object chat(@RequestBody ChatRequest request,
                       @RequestHeader(value = "Accept", defaultValue = "application/json") String accept) {
        if (accept.contains("text/event-stream")) {
            SseEmitter emitter = new SseEmitter(120_000L);
            emitter.onTimeout(() -> emitter.complete());
            try {
                llmService.chatStreaming(request, emitter);
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (java.io.IOException ioEx) { /* ignore */ }
                emitter.completeWithError(e);
            }
            return emitter;
        }
        // fallback: synchronous response
        try {
            JsonNode result = llmService.chat(request.getNodes(), request.getEdges(), request.getMessage(), request.getModelOverride(), request.getConfigId(), request.getHistory());
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}

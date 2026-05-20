package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuiyan.backend.model.ChatRequest;
import com.tuiyan.backend.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final LlmService llmService;

    public ChatController(LlmService llmService) {
        this.llmService = llmService;
    }

    @PostMapping
    public Object chat(@RequestBody ChatRequest request,
                       @RequestHeader(value = "Accept", defaultValue = "application/json") String accept) {
        if (accept.contains("text/event-stream")) {
            SseEmitter emitter = new SseEmitter(180_000L);
            AtomicBoolean cancelled = new AtomicBoolean(false);
            emitter.onCompletion(() -> cancelled.set(true));
            emitter.onTimeout(() -> {
                cancelled.set(true);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("LLM 响应超时（>180s），请检查 LLM 配置或网络后重试"));
                } catch (java.io.IOException ioErr) {
                    log.warn("ChatController timeout-emit failed: {}", ioErr.toString());
                }
                emitter.complete();
            });
            emitter.onError(t -> {
                cancelled.set(true);
                log.warn("ChatController emitter error: {}", t.toString());
            });
            try {
                llmService.chatStreaming(request, emitter);
            } catch (Exception e) {
                log.warn("ChatController chatStreaming threw: {}", e.toString(), e);
                try {
                    if (!cancelled.get()) emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (java.io.IOException ioEx) {
                    log.warn("emit error after exception failed: {}", ioEx.toString());
                }
                emitter.completeWithError(e);
            }
            return emitter;
        }
        try {
            JsonNode result = llmService.chat(request.getNodes(), request.getEdges(), request.getMessage(), request.getModelOverride(), request.getConfigId(), request.getHistory(), request.getAttachments());
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("ChatController chat threw: {}", e.toString(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}

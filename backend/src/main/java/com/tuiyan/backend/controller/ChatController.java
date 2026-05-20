package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuiyan.backend.model.ChatRequest;
import com.tuiyan.backend.service.LlmService;
import com.tuiyan.backend.support.SsePushUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final LlmService llmService;

    public ChatController(LlmService llmService) {
        this.llmService = llmService;
    }

    @PostMapping
    public Object chat(@RequestBody ChatRequest request,
                       @RequestHeader(value = "Accept", defaultValue = "application/json") String accept) throws Exception {
        if (accept.contains("text/event-stream")) {
            SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(180_000L);
            llmService.chatStreaming(request, ce.emitter());
            return ce.emitter();
        }
        JsonNode result = llmService.chat(request.getNodes(), request.getEdges(), request.getMessage(),
                request.getModelOverride(), request.getConfigId(), request.getHistory(), request.getAttachments());
        return ResponseEntity.ok(result);
    }
}

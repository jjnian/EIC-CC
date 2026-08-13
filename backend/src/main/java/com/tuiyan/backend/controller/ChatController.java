package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuiyan.backend.model.ChatRequest;
import com.tuiyan.backend.model.dto.ApiResult;
import com.tuiyan.backend.service.ChatLlmService;
import com.tuiyan.backend.support.SsePushUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 聊天接口：同步 JSON 与 SSE 流式拆成两个独立端点，避免 controller 里做 Accept 头分流。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatLlmService chatLlmService;

    public ChatController(ChatLlmService chatLlmService) {
        this.chatLlmService = chatLlmService;
    }

    @PostMapping
    public ApiResult<JsonNode> chat(@RequestBody ChatRequest request) throws IOException {
        JsonNode result = chatLlmService.chat(
                request.getNodes(), request.getEdges(), request.getMessage(),
                request.getModelOverride(), request.getConfigId(),
                request.getHistory(), request.getAttachments());
        return ApiResult.ok(result);
    }

    @PostMapping("/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(180_000L);
        chatLlmService.chatStreaming(request, ce.emitter());
        return ce.emitter();
    }
}

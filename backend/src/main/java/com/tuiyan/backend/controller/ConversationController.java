package com.tuiyan.backend.controller;

import com.tuiyan.backend.exception.ResourceNotFoundException;
import com.tuiyan.backend.model.Conversation;
import com.tuiyan.backend.model.dto.ApiResult;
import com.tuiyan.backend.model.dto.SuccessResponse;
import com.tuiyan.backend.service.ConversationService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService svc;

    public ConversationController(ConversationService svc) {
        this.svc = svc;
    }

    @GetMapping
    public ApiResult<List<Conversation>> list(@RequestParam(required = false) String workspaceId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            return ApiResult.ok(svc.list(workspaceId));
        }
        return ApiResult.ok(svc.list());
    }

    @GetMapping("/{id}")
    public ApiResult<Conversation> get(@PathVariable String id) throws IOException {
        Conversation c = svc.get(id);
        if (c == null) {
            throw new ResourceNotFoundException("会话不存在");
        }
        return ApiResult.ok(c);
    }

    @PostMapping
    public ApiResult<Conversation> create(@RequestBody Conversation c) throws IOException {
        return ApiResult.ok(svc.save(c));
    }

    @PutMapping("/{id}")
    public ApiResult<Conversation> update(@PathVariable String id, @RequestBody Conversation c) throws IOException {
        return ApiResult.ok(svc.update(id, c));
    }

    @DeleteMapping("/{id}")
    public ApiResult<SuccessResponse> delete(@PathVariable String id) {
        return ApiResult.ok(new SuccessResponse(svc.delete(id)));
    }
}

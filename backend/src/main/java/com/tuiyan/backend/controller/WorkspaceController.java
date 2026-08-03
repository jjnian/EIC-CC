package com.tuiyan.backend.controller;

import com.tuiyan.backend.entity.WorkspacePO;
import com.tuiyan.backend.model.dto.ApiResult;
import com.tuiyan.backend.model.dto.SuccessCountResponse;
import com.tuiyan.backend.service.WorkspaceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService svc;

    public WorkspaceController(WorkspaceService svc) {
        this.svc = svc;
    }

    @GetMapping
    public ApiResult<List<WorkspacePO>> list() {
        return ApiResult.ok(svc.list());
    }

    @GetMapping("/{id}")
    public ApiResult<WorkspacePO> get(@PathVariable String id) {
        return ApiResult.ok(svc.get(id));
    }

    @PostMapping
    public ApiResult<WorkspacePO> create(@RequestBody WorkspacePO input) {
        return ApiResult.ok(svc.create(input));
    }

    @PutMapping("/{id}")
    public ApiResult<WorkspacePO> update(@PathVariable String id, @RequestBody WorkspacePO input) {
        return ApiResult.ok(svc.update(id, input));
    }

    @DeleteMapping("/{id}")
    public ApiResult<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = svc.delete(id);
        return ApiResult.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }
}

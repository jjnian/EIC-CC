package com.tuiyan.backend.controller;

import com.tuiyan.backend.entity.WorkspacePO;
import com.tuiyan.backend.model.dto.SuccessCountResponse;
import com.tuiyan.backend.service.WorkspaceService;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<List<WorkspacePO>> list() {
        return ResponseEntity.ok(svc.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkspacePO> get(@PathVariable String id) {
        return ResponseEntity.ok(svc.get(id));
    }

    @PostMapping
    public ResponseEntity<WorkspacePO> create(@RequestBody WorkspacePO input) {
        return ResponseEntity.ok(svc.create(input));
    }

    @PutMapping("/{id}")
    public ResponseEntity<WorkspacePO> update(@PathVariable String id, @RequestBody WorkspacePO input) {
        return ResponseEntity.ok(svc.update(id, input));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = svc.delete(id);
        return ResponseEntity.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }
}

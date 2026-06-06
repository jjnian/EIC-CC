package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.dto.ExperienceCreateRequest;
import com.tuiyan.backend.model.dto.ExperienceUpdateRequest;
import com.tuiyan.backend.model.dto.SuccessCountResponse;
import com.tuiyan.backend.repository.ExperienceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 经验库端点：列表 / 详情 / 创建 / 编辑 / 删除，按工作空间隔离。
 * <p>与「数据源 / 历史记录」同级别挂在工作空间下。
 */
@RestController
@RequestMapping("/api/experiences")
public class ExperienceController {

    private final ExperienceRepository repo;

    public ExperienceController(ExperienceRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String workspaceId,
            @RequestParam(name = "all", defaultValue = "false") boolean all) {
        List<Map<String, Object>> list = all
                ? repo.listAll()
                : (workspaceId != null && !workspaceId.isBlank() ? repo.list(workspaceId) : repo.list());
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String id) {
        Map<String, Object> exp = repo.findFull(id);
        return exp == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(exp);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody ExperienceCreateRequest req) {
        return ResponseEntity.ok(repo.create(req.getTitle(), req.getContent(), req.getTags()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id,
                                                      @RequestBody ExperienceUpdateRequest req) {
        Map<String, Object> exp = repo.update(id, req.getTitle(), req.getContent(), req.getTags());
        return exp == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(exp);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = repo.delete(id);
        return ResponseEntity.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }
}

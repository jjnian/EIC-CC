package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.dto.ExperienceCreateRequest;
import com.tuiyan.backend.model.dto.ExperienceUpdateRequest;
import com.tuiyan.backend.model.dto.SuccessCountResponse;
import com.tuiyan.backend.repository.ExperienceRepository;
import com.tuiyan.backend.service.indexing.ExperienceIndexService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 经验库端点：列表 / 详情 / 创建 / 编辑 / 删除 + 向量索引，按工作空间隔离。
 * <p>与「数据源 / 历史记录」同级别挂在工作空间下；保存后自动重建 RAG 索引。
 */
@RestController
@RequestMapping("/api/experiences")
public class ExperienceController {

    private final ExperienceRepository repo;
    private final ExperienceIndexService indexService;

    public ExperienceController(ExperienceRepository repo, ExperienceIndexService indexService) {
        this.repo = repo;
        this.indexService = indexService;
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
        Map<String, Object> exp = repo.create(req.getTitle(), req.getContent(), req.getTags());
        triggerReindex(exp);
        return ResponseEntity.ok(exp);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id,
                                                      @RequestBody ExperienceUpdateRequest req) {
        Map<String, Object> exp = repo.update(id, req.getTitle(), req.getContent(), req.getTags());
        if (exp == null) return ResponseEntity.notFound().build();
        triggerReindex(exp);
        return ResponseEntity.ok(exp);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = repo.delete(id);
        return ResponseEntity.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    /** 手动重建索引（embedding 配置变更后补建用）。返回是否已触发。 */
    @PostMapping("/{id}/reindex")
    public ResponseEntity<Map<String, Object>> reindex(@PathVariable String id) {
        boolean configured = indexService.isConfigured();
        if (configured) indexService.reindexAsync(id);
        return ResponseEntity.ok(Map.of("triggered", configured, "configured", configured));
    }

    @GetMapping("/{id}/index-status")
    public ResponseEntity<Map<String, Object>> indexStatus(@PathVariable String id) {
        return ResponseEntity.ok(indexService.getIndexStatus(id));
    }

    /** 创建/编辑成功后异步重建该条经验的向量索引；未配置 embedding 时静默跳过。 */
    private void triggerReindex(Map<String, Object> exp) {
        Object id = exp == null ? null : exp.get("id");
        if (id != null) indexService.reindexAsync(String.valueOf(id));
    }
}

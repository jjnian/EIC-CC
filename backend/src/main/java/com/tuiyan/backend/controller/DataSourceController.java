package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.dto.SuccessCountResponse;
import com.tuiyan.backend.repository.DataSourceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据源接口：仅暴露当前工作空间下的「已导入文档 / 网页」列表与删除。
 * 创建路径在 {@code /api/ontology-models/extract} 内部完成，不单独暴露 POST。
 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {

    private final DataSourceRepository repo;

    public DataSourceController(DataSourceRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(repo.list());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = repo.delete(id);
        return ResponseEntity.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }
}

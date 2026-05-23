package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.service.GraphTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/**
 * 图谱模板 REST 接口：提供模板的增删查功能。
 */
@RestController
@RequestMapping("/api/templates")
public class GraphTemplateController {

    private final GraphTemplateService svc;

    public GraphTemplateController(GraphTemplateService svc) {
        this.svc = svc;
    }

    /** 获取所有模板 */
    @GetMapping
    public ResponseEntity<List<OntologyModel>> list() {
        return ResponseEntity.ok(svc.list());
    }

    /** 保存模板（新建或更新） */
    @PostMapping
    public ResponseEntity<OntologyModel> create(@RequestBody OntologyModel m) throws IOException {
        return ResponseEntity.ok(svc.save(m));
    }

    /** 删除模板 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        svc.delete(id);
        return ResponseEntity.noContent().build();
    }
}

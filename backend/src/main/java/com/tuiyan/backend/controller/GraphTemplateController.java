package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.model.dto.ApiResult;
import com.tuiyan.backend.service.GraphTemplateService;
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
    public ApiResult<List<OntologyModel>> list() {
        return ApiResult.ok(svc.list());
    }

    /** 保存模板（新建或更新） */
    @PostMapping
    public ApiResult<OntologyModel> create(@RequestBody OntologyModel m) throws IOException {
        return ApiResult.ok(svc.save(m));
    }

    /** 删除模板 */
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id) {
        svc.delete(id);
        return ApiResult.ok();
    }
}

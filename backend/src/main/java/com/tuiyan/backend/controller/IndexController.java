package com.tuiyan.backend.controller;

import com.tuiyan.backend.service.indexing.DataSourceIndexService;
import com.tuiyan.backend.support.SsePushUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 数据源向量索引接口：触发索引构建、查询状态、删除索引。
 */
@RestController
@RequestMapping("/api/index")
public class IndexController {

    private final DataSourceIndexService indexService;

    public IndexController(DataSourceIndexService indexService) {
        this.indexService = indexService;
    }

    @PostMapping("/{dataSourceId}")
    public SseEmitter indexDataSource(@PathVariable String dataSourceId) {
        SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(300_000L);
        indexService.indexDataSource(dataSourceId, ce.emitter());
        return ce.emitter();
    }

    @GetMapping("/{dataSourceId}/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String dataSourceId) {
        return ResponseEntity.ok(indexService.getIndexStatus(dataSourceId));
    }

    @DeleteMapping("/{dataSourceId}")
    public ResponseEntity<Void> deleteIndex(@PathVariable String dataSourceId) {
        indexService.deleteIndex(dataSourceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/configured")
    public ResponseEntity<Map<String, Object>> isConfigured() {
        return ResponseEntity.ok(Map.of("configured", indexService.isConfigured()));
    }
}

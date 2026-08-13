package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.dto.ApiResult;
import com.tuiyan.backend.service.indexing.DataSourceIndexService;
import com.tuiyan.backend.support.SsePushUtils;
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

    /**
     * 批量索引工作空间下「已引用」的全部 file_stored 数据源。
     * <p>立即返回调度概况（total/scheduled/skipped），实际索引后台进行；
     * 单源进度仍可通过 {@code GET /api/index/{id}/status} 查询。
     *
     * @param force 传 true 连已索引的也重建；默认 false 只补未索引的
     */
    @PostMapping("/workspace/{workspaceId}/reindex-all")
    public ApiResult<Map<String, Object>> reindexWorkspace(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "false") boolean force) {
        return ApiResult.ok(indexService.reindexWorkspace(workspaceId, force));
    }

    @GetMapping("/{dataSourceId}/status")
    public ApiResult<Map<String, Object>> getStatus(@PathVariable String dataSourceId) {
        return ApiResult.ok(indexService.getIndexStatus(dataSourceId));
    }

    @DeleteMapping("/{dataSourceId}")
    public ApiResult<Void> deleteIndex(@PathVariable String dataSourceId) {
        indexService.deleteIndex(dataSourceId);
        return ApiResult.ok();
    }

    @GetMapping("/configured")
    public ApiResult<Map<String, Object>> isConfigured() {
        return ApiResult.ok(Map.of("configured", indexService.isConfigured()));
    }
}

package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.DataSourceFetchLogPO;
import com.tuiyan.backend.model.dto.*;
import com.tuiyan.backend.repository.DataSourceRepository;
import com.tuiyan.backend.service.DataSourceService;
import com.tuiyan.backend.service.SchemaOntologyService;
import com.tuiyan.backend.support.SsePushUtils;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源端点：列表 / 创建 / 编辑 / 删除 / 测试 / 数据库专用 / HTTPS 专用。
 * 所有 kind 走 POST /api/data-sources（JSON）。
 * <p>文件上传已迁移至经验库（POST /api/experiences/file）；遗留 file_stored 数据源仅保留只读列表与删除。
 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {

    private final DataSourceRepository repo;
    private final DataSourceService service;
    private final SchemaOntologyService schemaOntology;
    private final AsyncTaskExecutor taskExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DataSourceController(DataSourceRepository repo,
                                DataSourceService service,
                                SchemaOntologyService schemaOntology,
                                @Qualifier("predictionExecutor") AsyncTaskExecutor taskExecutor) {
        this.repo = repo;
        this.service = service;
        this.schemaOntology = schemaOntology;
        this.taskExecutor = taskExecutor;
    }

    // ---------- 列表 / CRUD ----------

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam(required = false) String workspaceId,
                                                          @RequestParam(required = false) String kind,
                                                          @RequestParam(name = "all", defaultValue = "false") boolean all) {
        List<Map<String, Object>> list = all
                ? repo.listAll()
                : (workspaceId != null && !workspaceId.isBlank() ? repo.list(workspaceId) : repo.list());
        if (kind != null && !kind.isBlank()) {
            list = list.stream().filter(m -> kind.equals(m.get("kind"))).toList();
        }
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String id) {
        return ResponseEntity.ok(service.findFull(id));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody DataSourceCreateRequest req) {
        return ResponseEntity.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id,
                                                      @RequestBody DataSourceUpdateRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = service.delete(id);
        return ResponseEntity.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    /** 移动数据源到指定文件夹：{folderId}。folderId 省略/null = 移到工作空间根。 */
    @PutMapping("/{id}/folder")
    public ResponseEntity<SuccessCountResponse> moveToFolder(@PathVariable String id,
                                                             @RequestBody(required = false) Map<String, Object> body) {
        Object f = body == null ? null : body.get("folderId");
        String folderId = f == null ? null : String.valueOf(f);
        boolean ok = repo.moveToFolder(id, folderId);
        return ResponseEntity.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<DataSourceTestResponse> test(@PathVariable String id) {
        return ResponseEntity.ok(service.test(id));
    }

    @PostMapping("/test-inline")
    public ResponseEntity<DataSourceTestResponse> testInline(@RequestBody DataSourceCreateRequest req) {
        return ResponseEntity.ok(service.testInline(req.getKind(), req.getConfig()));
    }

    // ---------- 数据库专用 ----------

    @GetMapping("/{id}/tables")
    public ResponseEntity<List<String>> tables(@PathVariable String id) {
        return ResponseEntity.ok(service.listTables(id));
    }

    @GetMapping("/{id}/tables/{name}/preview")
    public ResponseEntity<TablePreviewResponse> tablePreview(@PathVariable String id,
                                                             @PathVariable String name,
                                                             @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(service.previewTable(id, name, limit));
    }

    @PostMapping("/{id}/sql")
    public ResponseEntity<SqlExecuteResponse> sql(@PathVariable String id,
                                                  @RequestBody SqlExecuteRequest req) {
        return ResponseEntity.ok(service.executeSql(id, req));
    }

    /** 数据库 schema 内省：返回表 + 列 + 外键 + 唯一键。前端 UI 直接展示用。 */
    @GetMapping("/{id}/schema")
    public ResponseEntity<Map<String, Object>> schema(@PathVariable String id) {
        return ResponseEntity.ok(service.introspectSchema(id));
    }

    /**
     * 从数据库 schema 一键生成本体血缘图（SSE 流式）。
     * <p>事件序列：step (多次进度) → complete (携带 {nodes, edges, reply, salt}) → 结束。
     * 失败时发 error 事件。
     */
    @PostMapping(value = "/{id}/extract-ontology", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter extractOntology(@PathVariable String id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        String modelOverride = body == null ? null : (String) body.get("modelOverride");
        String configId = body == null ? null : (String) body.get("configId");
        String userHint = body == null ? null : (String) body.get("hint");
        String workspaceId = WorkspaceContext.get();

        SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(300_000L,
                "Schema → 本体提取超时 (>300s)，请稍后重试或减少表数量");
        SseEmitter emitter = ce.emitter();

        taskExecutor.execute(() -> {
            if (workspaceId != null) WorkspaceContext.set(workspaceId);
            try {
                SchemaOntologyService.StepSink step = (key, label) -> {
                    try {
                        String json = objectMapper.writeValueAsString(
                                Map.of("key", key, "label", label));
                        SsePushUtils.safeSend(emitter, ce.cancelled(), "step", json);
                    } catch (Exception ignore) {}
                };
                SchemaOntologyService.ExtractResult r =
                        schemaOntology.extractFromDataSource(id, modelOverride, configId, userHint, step);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("nodes", r.payload().path("nodes"));
                payload.put("edges", r.payload().path("edges"));
                payload.put("reply", r.payload().path("reply").asText(""));
                payload.put("salt", r.salt());
                payload.put("tableCount", r.tableCount());
                payload.put("fkCount", r.fkCount());
                SsePushUtils.safeSend(emitter, ce.cancelled(), "complete",
                        objectMapper.writeValueAsString(payload));
                emitter.complete();
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                SsePushUtils.safeSend(emitter, ce.cancelled(), "error", msg);
                emitter.complete();
            } finally {
                WorkspaceContext.clear();
            }
        });
        return emitter;
    }

    // ---------- HTTPS 专用 ----------

    @PostMapping("/{id}/execute")
    public ResponseEntity<HttpExecuteResponse> executeHttp(@PathVariable String id) {
        return ResponseEntity.ok(service.executeHttp(id));
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<List<DataSourceFetchLogPO>> logs(@PathVariable String id) {
        return ResponseEntity.ok(service.listLogs(id));
    }

    @PutMapping("/{id}/schedule")
    public ResponseEntity<SuccessCountResponse> schedule(@PathVariable String id,
                                                         @RequestBody HttpScheduleRequest req) {
        service.schedule(id, req);
        return ResponseEntity.ok(new SuccessCountResponse(true, 1));
    }
}

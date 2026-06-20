package com.tuiyan.backend.controller;

import com.tuiyan.backend.entity.DataSourceFetchLogPO;
import com.tuiyan.backend.model.dto.*;
import com.tuiyan.backend.repository.DataSourceRepository;
import com.tuiyan.backend.repository.NodeDataBindingRepository;
import com.tuiyan.backend.service.DataSourceService;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据源端点：列表 / 创建 / 编辑 / 删除 / 测试 / 数据库专用 / HTTPS 专用。
 * 所有 kind 走 POST /api/data-sources（JSON）。
 * <p>文件上传已迁移至经验库（POST /api/experiences/file）；遗留 file_stored 数据源仅保留只读列表与删除。
 * <p>本体血缘图不再由数据源直出：数据源结构经「导出 DDL 到经验库」沉淀后由经验库建图，
 * 数据源仅为建好的图节点绑定数据供血（见 {@code NodeBindingController}）。
 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {

    private final DataSourceRepository repo;
    private final DataSourceService service;
    private final NodeDataBindingRepository bindingRepo;

    public DataSourceController(DataSourceRepository repo,
                                DataSourceService service,
                                NodeDataBindingRepository bindingRepo) {
        this.repo = repo;
        this.service = service;
        this.bindingRepo = bindingRepo;
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

    /**
     * 「数据源 → 引用它的工作空间 id 列表」映射（跨工作空间）。
     * 公共数据源总览页据此展示每个数据源被哪些工作空间通过节点供血绑定引用。
     */
    @GetMapping("/references")
    public ResponseEntity<Map<String, List<String>>> references() {
        return ResponseEntity.ok(bindingRepo.referencingWorkspacesByDataSource());
    }

    /** 当前工作空间「尚未引用」的公共数据源（引用选择器列出可引入的数据源）。 */
    @GetMapping("/referencable")
    public ResponseEntity<List<Map<String, Object>>> referencable() {
        return ResponseEntity.ok(repo.listReferencable(WorkspaceContext.required()));
    }

    /** 把一批公共数据源引用进当前工作空间（已引用的跳过）。请求体：{ dataSourceIds: [...] }。 */
    @PostMapping("/refs")
    public ResponseEntity<Map<String, Object>> reference(@RequestBody Map<String, Object> body) {
        Object ids = body == null ? null : body.get("dataSourceIds");
        List<String> list = new java.util.ArrayList<>();
        if (ids instanceof List<?> arr) {
            for (Object o : arr) if (o != null) list.add(String.valueOf(o));
        }
        int added = repo.reference(list);
        return ResponseEntity.ok(Map.of("added", added));
    }

    /** 取消当前工作空间对某数据源的引用（不删除数据源本体）。 */
    @DeleteMapping("/{id}/ref")
    public ResponseEntity<SuccessCountResponse> unreference(@PathVariable String id) {
        boolean ok = repo.unreference(id);
        return ResponseEntity.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
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

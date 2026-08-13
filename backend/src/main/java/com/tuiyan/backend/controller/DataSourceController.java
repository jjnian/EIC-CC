package com.tuiyan.backend.controller;

import com.tuiyan.backend.entity.DataSourceFetchLogPO;
import com.tuiyan.backend.model.dto.*;
import com.tuiyan.backend.service.DataSourceService;
import com.tuiyan.backend.service.StructuralGraphService;
import com.tuiyan.backend.service.connector.JdbcConnectorService;
import com.tuiyan.backend.support.SseJobRunner;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
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

    private final DataSourceService service;
    private final StructuralGraphService structuralGraphService;
    private final SseJobRunner sseJobs;

    public DataSourceController(DataSourceService service,
                                StructuralGraphService structuralGraphService,
                                SseJobRunner sseJobs) {
        this.service = service;
        this.structuralGraphService = structuralGraphService;
        this.sseJobs = sseJobs;
    }

    /**
     * 确定性结构建图：从本数据源全库内省，把 表→节点、列→属性、外键→depends_on 血缘边
     * 直出为一个<b>新本体模型</b>（绕过 LLM，面向千张/万张表）。请求体：{@code {title?}}。
     * <p>这是「数据源直出图」的受控例外——仅结构层、确定性、可事后用 LLM 按域增量叠加业务语义。
     * 大库内省 + 落库可能耗时较长（同步返回）。
     */
    @PostMapping("/{id}/build-structural-graph")
    public ApiResult<StructuralGraphService.BuildResult> buildStructuralGraph(
            @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        String title = body == null ? null : asString(body.get("title"));
        return ApiResult.ok(structuralGraphService.buildFromDataSource(id, title));
    }

    /**
     * 结构建图（SSE 流式）：与上面同能力，但把内省+落库丢到后台线程、流式回进度，避免万张表大库
     * 同步请求 HTTP 超时。事件：{@code step}（进度）→ {@code complete}（BuildResult）/ {@code error}。
     */
    @PostMapping(value = "/{id}/build-structural-graph/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter buildStructuralGraphStream(@PathVariable String id,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        String title = body == null ? null : asString(body.get("title"));
        return sseJobs.run(1_800_000L, "结构建图超时（>30min），请对超大库分 schema 建图或稍后重试", "结构建图失败，请稍后重试",
                step -> structuralGraphService.buildFromDataSource(id, title, (k, l) -> step.emit(k, l)));
    }

    private static String asString(Object v) { return v == null ? null : String.valueOf(v); }

    // ---------- 列表 / CRUD ----------

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(required = false) String workspaceId,
                                                          @RequestParam(required = false) String kind,
                                                          @RequestParam(name = "all", defaultValue = "false") boolean all) {
        return ApiResult.ok(service.list(workspaceId, kind, all));
    }

    /**
     * 「数据源 → 引用它的工作空间 id 列表」映射（跨工作空间）。
     * 公共数据源总览页据此展示每个数据源被哪些工作空间通过节点供血绑定引用。
     */
    @GetMapping("/references")
    public ApiResult<Map<String, List<String>>> references() {
        return ApiResult.ok(service.references());
    }

    /** 当前工作空间「尚未引用」的公共数据源（引用选择器列出可引入的数据源）。 */
    @GetMapping("/referencable")
    public ApiResult<List<Map<String, Object>>> referencable() {
        return ApiResult.ok(service.referencable());
    }

    /** 把一批公共数据源引用进当前工作空间（已引用的跳过）。请求体：{ dataSourceIds: [...] }。 */
    @PostMapping("/refs")
    public ApiResult<Map<String, Object>> reference(@RequestBody Map<String, Object> body) {
        Object ids = body == null ? null : body.get("dataSourceIds");
        List<String> list = new ArrayList<>();
        if (ids instanceof List<?> arr) {
            for (Object o : arr) if (o != null) list.add(String.valueOf(o));
        }
        int added = service.reference(list);
        return ApiResult.ok(Map.of("added", added));
    }

    /** 取消当前工作空间对某数据源的引用（不删除数据源本体）。 */
    @DeleteMapping("/{id}/ref")
    public ApiResult<SuccessCountResponse> unreference(@PathVariable String id) {
        boolean ok = service.unreference(id);
        return ApiResult.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        return ApiResult.ok(service.findFull(id));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody DataSourceCreateRequest req) {
        return ApiResult.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable String id,
                                                      @RequestBody DataSourceUpdateRequest req) {
        return ApiResult.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResult<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = service.delete(id);
        return ApiResult.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    /** 移动数据源到指定文件夹：{folderId}。folderId 省略/null = 移到工作空间根。 */
    @PutMapping("/{id}/folder")
    public ApiResult<SuccessCountResponse> moveToFolder(@PathVariable String id,
                                                             @RequestBody(required = false) Map<String, Object> body) {
        Object f = body == null ? null : body.get("folderId");
        String folderId = f == null ? null : String.valueOf(f);
        boolean ok = service.moveToFolder(id, folderId);
        return ApiResult.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    @PostMapping("/{id}/test")
    public ApiResult<DataSourceTestResponse> test(@PathVariable String id) {
        return ApiResult.ok(service.test(id));
    }

    @PostMapping("/test-inline")
    public ApiResult<DataSourceTestResponse> testInline(@RequestBody DataSourceCreateRequest req) {
        return ApiResult.ok(service.testInline(req.getKind(), req.getConfig()));
    }

    // ---------- 数据库专用 ----------

    @GetMapping("/{id}/tables")
    public ApiResult<List<String>> tables(@PathVariable String id) {
        return ApiResult.ok(service.listTables(id));
    }

    @GetMapping("/{id}/tables/{name}/preview")
    public ApiResult<TablePreviewResponse> tablePreview(@PathVariable String id,
                                                             @PathVariable String name,
                                                             @RequestParam(defaultValue = "50") int limit) {
        return ApiResult.ok(service.previewTable(id, name, limit));
    }

    @PostMapping("/{id}/sql")
    public ApiResult<SqlExecuteResponse> sql(@PathVariable String id,
                                                  @RequestBody SqlExecuteRequest req) {
        return ApiResult.ok(service.executeSql(id, req));
    }

    /**
     * 血缘值包含检验：验证 childTable.childColumn 的值是否都能在 parentTable.parentColumn 中找到。
     * 体：{childTable, childColumn, parentTable, parentColumn, sampleLimit?}。
     * 用途：把「按命名推断」的血缘边升级为「数据证实」（confirmed/likely）或否掉（rejected）。
     */
    @PostMapping("/{id}/verify-containment")
    public ApiResult<JdbcConnectorService.ContainmentCheckResponse>
    verifyContainment(@PathVariable String id, @RequestBody Map<String, Object> body) {
        int sampleLimit = 0;
        Object sl = body.get("sampleLimit");
        if (sl instanceof Number n) sampleLimit = n.intValue();
        return ApiResult.ok(service.verifyContainment(id,
                str(body, "childTable"), str(body, "childColumn"),
                str(body, "parentTable"), str(body, "parentColumn"), sampleLimit));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v).trim();
    }

    /** 数据库 schema 内省：返回表 + 列 + 外键 + 唯一键。前端 UI 直接展示用。 */
    @GetMapping("/{id}/schema")
    public ApiResult<Map<String, Object>> schema(@PathVariable String id) {
        return ApiResult.ok(service.introspectSchema(id));
    }

    // ---------- HTTPS 专用 ----------

    @PostMapping("/{id}/execute")
    public ApiResult<HttpExecuteResponse> executeHttp(@PathVariable String id) {
        return ApiResult.ok(service.executeHttp(id));
    }

    @GetMapping("/{id}/logs")
    public ApiResult<List<DataSourceFetchLogPO>> logs(@PathVariable String id) {
        return ApiResult.ok(service.listLogs(id));
    }

    @PutMapping("/{id}/schedule")
    public ApiResult<SuccessCountResponse> schedule(@PathVariable String id,
                                                         @RequestBody HttpScheduleRequest req) {
        service.schedule(id, req);
        return ApiResult.ok(new SuccessCountResponse(true, 1));
    }
}

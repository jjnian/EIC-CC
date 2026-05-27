package com.tuiyan.backend.controller;

import com.tuiyan.backend.entity.DataSourceFetchLogPO;
import com.tuiyan.backend.model.dto.*;
import com.tuiyan.backend.repository.DataSourceRepository;
import com.tuiyan.backend.service.DataSourceService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 数据源端点：列表 / 创建 / 编辑 / 删除 / 测试 / 数据库专用 / 文件专用 / HTTPS 专用。
 * 创建路径 file_stored 走 /api/data-sources/file（multipart）；
 * 其他 kind 走 POST /api/data-sources（JSON）。
 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {

    private final DataSourceRepository repo;
    private final DataSourceService service;

    public DataSourceController(DataSourceRepository repo, DataSourceService service) {
        this.repo = repo;
        this.service = service;
    }

    // ---------- 列表 / CRUD ----------

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam(required = false) String workspaceId,
                                                          @RequestParam(required = false) String kind) {
        List<Map<String, Object>> all = (workspaceId != null && !workspaceId.isBlank())
                ? repo.list(workspaceId) : repo.list();
        if (kind != null && !kind.isBlank()) {
            all = all.stream().filter(m -> kind.equals(m.get("kind"))).toList();
        }
        return ResponseEntity.ok(all);
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

    // ---------- 文件专用 ----------

    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file,
                                                          @RequestParam(value = "name", required = false) String name)
            throws IOException {
        return ResponseEntity.ok(service.ingestFile(name, file));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<String> content(@PathVariable String id,
                                          @RequestParam(defaultValue = "0") int offset,
                                          @RequestParam(defaultValue = "10000") int length) throws IOException {
        return ResponseEntity.ok(service.readFileText(id, offset, length));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable String id) {
        File f = service.originalFileFor(id);
        if (f == null) return ResponseEntity.notFound().build();
        String encoded = URLEncoder.encode(f.getName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(f));
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

package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.model.dto.SuccessCountResponse;
import com.tuiyan.backend.service.DocumentExtractionService;
import com.tuiyan.backend.service.OntologyModelService;
import com.tuiyan.backend.service.extraction.UploadedFile;
import com.tuiyan.backend.support.SsePushUtils;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology-models")
public class OntologyModelController {

    private final OntologyModelService svc;
    private final DocumentExtractionService extractionService;

    public OntologyModelController(OntologyModelService svc, DocumentExtractionService extractionService) {
        this.svc = svc;
        this.extractionService = extractionService;
    }

    @GetMapping
    public ResponseEntity<List<OntologyModel>> list() throws IOException {
        return ResponseEntity.ok(svc.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OntologyModel> get(@PathVariable String id) throws IOException {
        OntologyModel m = svc.get(id);
        return m == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(m);
    }

    @PostMapping
    public ResponseEntity<OntologyModel> create(@RequestBody OntologyModel m) throws IOException {
        return ResponseEntity.ok(svc.save(m));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OntologyModel> update(@PathVariable String id, @RequestBody OntologyModel m) throws IOException {
        return ResponseEntity.ok(svc.update(id, m));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = svc.delete(id);
        return ResponseEntity.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<Map<String, Object>>> listVersions(@PathVariable String id) {
        return ResponseEntity.ok(svc.listVersions(id));
    }

    @PostMapping("/{id}/versions/{timestamp}/restore")
    public ResponseEntity<OntologyModel> restoreVersion(@PathVariable String id, @PathVariable long timestamp) throws IOException {
        return ResponseEntity.ok(svc.restoreVersion(id, timestamp));
    }

    @PostMapping(value = "/extract", consumes = {"multipart/form-data"})
    public ResponseEntity<Map<String, Object>> extract(
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "urls", required = false) List<String> urls,
            @RequestParam(value = "modelOverride", required = false) String modelOverride,
            @RequestParam(value = "configId", required = false) String configId) throws Exception {
        return ResponseEntity.ok(extractionService.extract(
                files == null ? List.of() : files,
                urls == null ? List.of() : urls,
                modelOverride, configId));
    }

    /**
     * 流式抽取：SSE 实时推送抽取的每个阶段（step 事件），完成时发 complete 事件携带结果。
     * <p>multipart 字节需在请求线程内提前读入内存；workspaceId 也在此捕获后透传给后台线程，
     * 因为 WorkspaceInterceptor 的 ThreadLocal 不会随异步任务传播。
     */
    @PostMapping(value = "/extract/stream", consumes = {"multipart/form-data"})
    public SseEmitter extractStream(
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "urls", required = false) List<String> urls,
            @RequestParam(value = "modelOverride", required = false) String modelOverride,
            @RequestParam(value = "configId", required = false) String configId) throws IOException {
        List<UploadedFile> uploaded = new ArrayList<>();
        if (files != null) {
            for (MultipartFile f : files) {
                if (f == null || f.getSize() <= 0) continue;
                uploaded.add(new UploadedFile(
                        f.getOriginalFilename(), f.getContentType(), f.getSize(), f.getBytes()));
            }
        }
        String workspaceId = WorkspaceContext.get();
        SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(300_000L);
        extractionService.extractStreaming(uploaded,
                urls == null ? List.of() : urls,
                modelOverride, configId, workspaceId, ce.emitter());
        return ce.emitter();
    }
}

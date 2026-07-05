package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.model.dto.SuccessCountResponse;
import com.tuiyan.backend.service.DocumentExtractionService;
import com.tuiyan.backend.service.GraphQueryService;
import com.tuiyan.backend.service.LineageTraversalService;
import com.tuiyan.backend.repository.ModelBuildSourceRepository;
import com.tuiyan.backend.service.OntologyModelService;
import com.tuiyan.backend.service.SchemaDriftService;
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
    private final LineageTraversalService lineageService;
    private final ModelBuildSourceRepository buildSourceRepo;
    private final SchemaDriftService schemaDriftService;
    private final com.tuiyan.backend.service.GraphQueryService graphQueryService;
    private final com.tuiyan.backend.service.GraphPatchService graphPatchService;
    private final com.tuiyan.backend.service.GraphChatEditService graphChatEditService;

    public OntologyModelController(OntologyModelService svc,
                                   DocumentExtractionService extractionService,
                                   LineageTraversalService lineageService,
                                   ModelBuildSourceRepository buildSourceRepo,
                                   SchemaDriftService schemaDriftService,
                                   com.tuiyan.backend.service.GraphQueryService graphQueryService,
                                   com.tuiyan.backend.service.GraphPatchService graphPatchService,
                                   com.tuiyan.backend.service.GraphChatEditService graphChatEditService) {
        this.svc = svc;
        this.extractionService = extractionService;
        this.lineageService = lineageService;
        this.buildSourceRepo = buildSourceRepo;
        this.schemaDriftService = schemaDriftService;
        this.graphQueryService = graphQueryService;
        this.graphPatchService = graphPatchService;
        this.graphChatEditService = graphChatEditService;
    }

    /**
     * 外科手术式局部编辑：对已落库的模型只改动指定节点/边（增/删/改），不加载/不重存整图——
     * 面向大图快速修复，也是对话驱动改图的落地入口。请求体：{@code {ops:[{op,node?/edge?/id}]}}。
     * 含归属校验。返回 {applied, skipped, nodeCount, edgeCount}。
     */
    @PostMapping("/{id}/patch")
    public ResponseEntity<com.tuiyan.backend.service.GraphPatchService.PatchResult> patch(
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        Object opsObj = body == null ? null : body.get("ops");
        List<Map<String, Object>> ops = new ArrayList<>();
        if (opsObj instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) m;
                ops.add(mm);
            }
        }
        return ResponseEntity.ok(graphPatchService.apply(id, ops));
    }

    /**
     * 对话驱动精准改图：用自然语言描述要怎么改（连边/删节点/改方向/改命名…），系统只检索相关子图喂 LLM
     * （不发整图，大图也能改），LLM 产精确 patch 后局部应用。请求体：{@code {message, modelOverride?, configId?}}。
     * 返回 {reply, applied, skipped, ops, nodeCount, edgeCount}。
     */
    @PostMapping("/{id}/chat-edit")
    public ResponseEntity<com.tuiyan.backend.service.GraphChatEditService.EditResult> chatEdit(
            @PathVariable String id, @RequestBody Map<String, Object> body) throws IOException {
        String message = body == null ? null : asString(body.get("message"));
        String modelOverride = body == null ? null : asString(body.get("modelOverride"));
        String configId = body == null ? null : asString(body.get("configId"));
        // 可选：前端传来「当前可见子图」的节点范围作上下文（免读整图、更精准）
        List<String> scopeNodeIds = new ArrayList<>();
        Object scope = body == null ? null : body.get("scopeNodeIds");
        if (scope instanceof List<?> list) for (Object o : list) if (o != null) scopeNodeIds.add(String.valueOf(o));
        return ResponseEntity.ok(graphChatEditService.chatEdit(id, message, scopeNodeIds, modelOverride, configId));
    }

    private static String asString(Object v) { return v == null ? null : String.valueOf(v); }

    /**
     * 大图规模摘要：{nodeCount, edgeCount, domainCount}。前端据此判定是否进「大图模式」
     * （不整图渲染），避免万节点 SVG 卡死浏览器。含归属校验，不加载整图。
     */
    @GetMapping("/{id}/summary")
    public ResponseEntity<Map<String, Object>> summary(@PathVariable String id) {
        return ResponseEntity.ok(graphQueryService.summary(id));
    }

    /**
     * 聚焦子图：从 {@code node} 出发取 {@code depth} 跳邻域（节点封顶 {@code limit}），供大图按节点浏览。
     * {@code node} 为空时返回度数最高的入口枢纽。{@code dir}: up/down/both（默认 both）。
     */
    @GetMapping("/{id}/subgraph")
    public ResponseEntity<Map<String, Object>> subgraph(@PathVariable String id,
                                                        @RequestParam(required = false) String node,
                                                        @RequestParam(defaultValue = "2") int depth,
                                                        @RequestParam(defaultValue = "both") String dir,
                                                        @RequestParam(defaultValue = "300") int limit) {
        GraphQueryService.Dir d = "up".equalsIgnoreCase(dir) ? GraphQueryService.Dir.UP
                : "down".equalsIgnoreCase(dir) ? GraphQueryService.Dir.DOWN : GraphQueryService.Dir.BOTH;
        return ResponseEntity.ok(graphQueryService.subgraph(id, node, depth, d, limit));
    }

    /** 领域汇总：把节点按 domain 聚成超级节点 + 跨领域流向计数，供大图的领域总览/钻取。 */
    @GetMapping("/{id}/domains")
    public ResponseEntity<Map<String, Object>> domains(@PathVariable String id) {
        return ResponseEntity.ok(graphQueryService.domainRollup(id));
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

    /**
     * 血缘上下游遍历：从节点出发，按 rel_type 语义判定的数据流方向遍历（非死按 from→to）。
     * @param node                起点节点 id
     * @param direction           upstream(上游来源) | downstream(下游派生)，默认 downstream
     * @param depth               最大跳数，≤0 不限，默认不限
     * @param includeAssociations 是否把 associated_with 等无方向的纯关联边纳入遍历，默认 false
     */
    @GetMapping("/{id}/lineage")
    public ResponseEntity<Map<String, Object>> lineage(@PathVariable String id,
                                                       @RequestParam String node,
                                                       @RequestParam(defaultValue = "downstream") String direction,
                                                       @RequestParam(defaultValue = "0") int depth,
                                                       @RequestParam(defaultValue = "false") boolean includeAssociations) {
        if (svc.get(id) == null) return ResponseEntity.notFound().build(); // 归属校验：非本工作空间模型不可读
        LineageTraversalService.Direction dir = "upstream".equalsIgnoreCase(direction)
                ? LineageTraversalService.Direction.UPSTREAM
                : LineageTraversalService.Direction.DOWNSTREAM;
        return ResponseEntity.ok(lineageService.traverse(id, node, dir, depth, includeAssociations));
    }

    /**
     * Schema 漂移检测：重新内省数据源最新 schema，比对图上 derived_tables / 属性 column 引用，
     * 报告已失效的表/列与受影响的节点/边。体：{dataSourceId}。只读。
     */
    @PostMapping("/{id}/schema-drift")
    public ResponseEntity<Map<String, Object>> schemaDrift(@PathVariable String id,
                                                           @RequestBody Map<String, Object> body) throws IOException {
        if (svc.get(id) == null) return ResponseEntity.notFound().build();
        Object ds = body == null ? null : body.get("dataSourceId");
        if (ds == null || String.valueOf(ds).isBlank()) {
            throw new IllegalArgumentException("缺少 dataSourceId");
        }
        return ResponseEntity.ok(schemaDriftService.detect(id, String.valueOf(ds)));
    }

    /**
     * 回写建图来源记录：前端把经验库建图结果合并进模型后调用，记录「本模型由哪些经验的哪个
     * 内容版本构建」。下次增量建图据此跳过未变更经验。体：{sources:[{experienceId, contentHash}]}。
     */
    @PostMapping("/{id}/build-sources")
    public ResponseEntity<Map<String, Object>> recordBuildSources(@PathVariable String id,
                                                                  @RequestBody Map<String, Object> body) throws IOException {
        if (svc.get(id) == null) return ResponseEntity.notFound().build(); // 归属校验：不可写别的工作空间的模型记录
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        Object src = body == null ? null : body.get("sources");
        if (src instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object eid = m.get("experienceId");
                    Object hash = m.get("contentHash");
                    if (eid != null && hash != null) {
                        entries.put(String.valueOf(eid), String.valueOf(hash));
                    }
                }
            }
        }
        buildSourceRepo.upsertAll(id, entries);
        return ResponseEntity.ok(Map.of("recorded", entries.size()));
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

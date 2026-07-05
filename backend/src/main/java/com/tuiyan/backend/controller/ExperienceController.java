package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.model.dto.ExperienceCreateRequest;
import com.tuiyan.backend.model.dto.ExperienceUpdateRequest;
import com.tuiyan.backend.model.dto.SuccessCountResponse;
import com.tuiyan.backend.repository.ExperienceRepository;
import com.tuiyan.backend.service.ExperienceFileService;
import com.tuiyan.backend.service.ExperienceOntologyService;
import com.tuiyan.backend.service.WebResearchService;
import com.tuiyan.backend.service.WebSystemConfigAssembler;
import com.tuiyan.backend.service.indexing.ExperienceIndexService;
import com.tuiyan.backend.entity.ExperiencePO;
import com.tuiyan.backend.service.storage.ObjectStorage;
import com.tuiyan.backend.support.SsePushUtils;
import com.tuiyan.backend.support.WebUrls;
import com.tuiyan.backend.support.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经验库端点：列表 / 详情 / 创建 / 编辑 / 删除 + 向量索引，按工作空间隔离。
 * <p>与「数据源 / 历史记录」同级别挂在工作空间下；保存后自动重建 RAG 索引。
 * 文件/DDL 来源构建委托 {@link ExperienceFileService}，web 系统配置组装委托 {@link WebSystemConfigAssembler}。
 */
@RestController
@RequestMapping("/api/experiences")
public class ExperienceController {

    private final ExperienceRepository repo;
    private final ExperienceIndexService indexService;
    private final ExperienceFileService fileService;
    private final ExperienceOntologyService experienceOntology;
    private final WebResearchService webResearchService;
    private final ObjectStorage storage;
    private final AsyncTaskExecutor taskExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(ExperienceController.class);
    /** 上传原件归档前缀，复用写入方的常量，保证删除清理与归档路径永远一致。 */
    private static final String FILE_PREFIX = ExperienceFileService.FILE_PREFIX;

    public ExperienceController(ExperienceRepository repo,
                                ExperienceIndexService indexService,
                                ExperienceFileService fileService,
                                ExperienceOntologyService experienceOntology,
                                WebResearchService webResearchService,
                                ObjectStorage storage,
                                @Qualifier("appTaskExecutor") AsyncTaskExecutor taskExecutor) {
        this.repo = repo;
        this.indexService = indexService;
        this.fileService = fileService;
        this.experienceOntology = experienceOntology;
        this.webResearchService = webResearchService;
        this.storage = storage;
        this.taskExecutor = taskExecutor;
    }

    /**
     * SSE 错误事件的对客文案：与 {@link com.tuiyan.backend.config.GlobalExceptionHandler} 同策略——
     * 受控业务异常（{@link IllegalArgumentException}/{@link IllegalStateException}，message 由我们自己写）
     * 原样回传；其它未预期异常（JDBC/LLM 客户端/NPE 等，message 可能含连接串、内部路径、SQL 片段）
     * 只在服务端记全栈，对外统一回退通用文案，避免经 SSE error 事件泄露内部细节。
     * <p>SSE 在 emitter 建立后异常无法走全局处理器，故各 SSE 端点需自行经此收敛错误文案。
     */
    private static String clientSafeError(Throwable e, String fallback) {
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            String msg = e.getMessage();
            if (msg != null && !msg.isBlank()) return msg;
        }
        log.warn("[experience] SSE 任务未预期异常: {}", e.toString(), e);
        return fallback;
    }

    /**
     * 联网调研业务知识（SSE 流式）：搜索主题 → 抓取命中网页 → LLM 归纳成《业务知识文档》
     * → 存为经验（origin=websearch，自动引用进当前工作空间 + 建索引）。
     * 体：{topic, maxPages?, modelOverride?, configId?}。事件：step* → complete{experience} / error。
     */
    @PostMapping(value = "/web-research", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter webResearch(@RequestBody Map<String, Object> body) {
        String topic = body == null ? null : (String) body.get("topic");
        String modelOverride = body == null ? null : (String) body.get("modelOverride");
        String configId = body == null ? null : (String) body.get("configId");
        int maxPages = 0;
        Object mp = body == null ? null : body.get("maxPages");
        if (mp instanceof Number n) maxPages = n.intValue();
        final int pages = maxPages;
        String workspaceId = WorkspaceContext.get();

        SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(300_000L,
                "联网调研超时 (>300s)，请稍后重试或减少抓取页数");
        SseEmitter emitter = ce.emitter();
        taskExecutor.execute(() -> {
            if (workspaceId != null) WorkspaceContext.set(workspaceId);
            try {
                ExperienceOntologyService.StepSink step = (key, label) -> {
                    try {
                        String json = objectMapper.writeValueAsString(Map.of("key", key, "label", label));
                        SsePushUtils.safeSend(emitter, ce.cancelled(), "step", json);
                    } catch (Exception ignore) {}
                };
                Map<String, Object> exp = webResearchService.research(topic, pages, modelOverride, configId, step);
                SsePushUtils.safeSend(emitter, ce.cancelled(), "complete",
                        objectMapper.writeValueAsString(Map.of("experience", exp)));
                emitter.complete();
            } catch (Exception e) {
                try {
                    SsePushUtils.safeSend(emitter, ce.cancelled(), "error",
                            clientSafeError(e, "联网调研失败，请稍后重试"));
                } catch (Exception ignore) {}
                emitter.complete();
            } finally {
                if (workspaceId != null) WorkspaceContext.clear();
            }
        });
        return emitter;
    }

    /**
     * 从「当前工作空间的整个经验库」一键构建本体血缘图（SSE 流式）。
     * <p>事件序列：step（多次进度）→ complete（携带 {nodes, edges, reply, salt, sourceCount}）→ 结束；
     * 失败时发 error 事件。这是新数据流的主入口：本体血缘图由经验库文件构建，数据源只负责供血。
     */
    @PostMapping(value = "/extract-ontology", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter extractOntology(@RequestBody(required = false) Map<String, Object> body) {
        String modelOverride = body == null ? null : (String) body.get("modelOverride");
        String configId = body == null ? null : (String) body.get("configId");
        String userHint = body == null ? null : (String) body.get("hint");
        // 可选建图范围：experienceIds 非空时只聚合这些经验，缺省聚合本空间全部
        List<String> experienceIds = null;
        Object idsObj = body == null ? null : body.get("experienceIds");
        if (idsObj instanceof List<?> rawIds && !rawIds.isEmpty()) {
            experienceIds = rawIds.stream().map(String::valueOf).filter(v -> !v.isBlank()).toList();
        }
        final List<String> scopeIds = experienceIds;
        // 增量建图：传入目标模型 id 时按其构建记录跳过未变更经验
        final String incrementalModelId = body == null ? null : (String) body.get("incrementalModelId");
        String workspaceId = WorkspaceContext.get();

        SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(300_000L,
                "经验库 → 本体提取超时 (>300s)，请稍后重试或精简经验库内容");
        SseEmitter emitter = ce.emitter();

        taskExecutor.execute(() -> {
            if (workspaceId != null) WorkspaceContext.set(workspaceId);
            try {
                ExperienceOntologyService.StepSink step = (key, label) -> {
                    try {
                        String json = objectMapper.writeValueAsString(Map.of("key", key, "label", label));
                        SsePushUtils.safeSend(emitter, ce.cancelled(), "step", json);
                    } catch (Exception ignore) {}
                };
                ExperienceOntologyService.ExtractResult r =
                        experienceOntology.extractFromWorkspace(modelOverride, configId, userHint,
                                scopeIds, incrementalModelId, step);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("nodes", r.payload().path("nodes"));
                payload.put("edges", r.payload().path("edges"));
                payload.put("reply", r.payload().path("reply").asText(""));
                payload.put("salt", r.salt());
                payload.put("sourceCount", r.sourceCount());
                payload.put("incremental", r.payload().path("incremental").asBoolean(false));
                payload.put("skippedUnchanged", r.payload().path("skippedUnchanged").asInt(0));
                payload.put("manifest", r.payload().path("manifest"));
                SsePushUtils.safeSend(emitter, ce.cancelled(), "complete",
                        objectMapper.writeValueAsString(payload));
                emitter.complete();
            } catch (Exception e) {
                SsePushUtils.safeSend(emitter, ce.cancelled(), "error",
                        clientSafeError(e, "建图失败，请稍后重试"));
                emitter.complete();
            } finally {
                WorkspaceContext.clear();
            }
        });
        return emitter;
    }

    /**
     * 全量建图（SSE 流式，面向海量经验：千个/万个）：不封顶单次经验数，分域分批处理<b>全部</b>经验，
     * <b>服务端直接落成一个新模型</b>并回写构建记录（不把万节点 payload 回传前端合并）。
     * <p>与 {@code extract-ontology} 的区别：无 500 上限、长超时、服务端落库、返回摘要而非整图。
     * 事件：{@code step}（进度）→ {@code complete}（{modelId, nodeCount, edgeCount, sourceCount, title}）/ {@code error}。
     */
    @PostMapping(value = "/build-full", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter buildFull(@RequestBody(required = false) Map<String, Object> body) {
        String modelOverride = body == null ? null : (String) body.get("modelOverride");
        String configId = body == null ? null : (String) body.get("configId");
        String userHint = body == null ? null : (String) body.get("hint");
        String title = body == null ? null : (String) body.get("title");
        String workspaceId = WorkspaceContext.get();

        // 海量经验 = 成千上万次 LLM 调用，给足超时（30min）
        SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(
                1_800_000L, "全量建图超时（>30min），请分域建图或减少经验范围后重试");
        SseEmitter emitter = ce.emitter();
        taskExecutor.execute(() -> {
            if (workspaceId != null) WorkspaceContext.set(workspaceId);
            try {
                ExperienceOntologyService.StepSink step = (key, label) -> {
                    try {
                        String json = objectMapper.writeValueAsString(Map.of("key", key, "label", label));
                        SsePushUtils.safeSend(emitter, ce.cancelled(), "step", json);
                    } catch (Exception ignore) {}
                };
                ExperienceOntologyService.BuildIntoModelResult r =
                        experienceOntology.buildFullIntoNewModel(modelOverride, configId, userHint, title, step);
                SsePushUtils.safeSend(emitter, ce.cancelled(), "complete", objectMapper.writeValueAsString(r));
                emitter.complete();
            } catch (Exception e) {
                SsePushUtils.safeSend(emitter, ce.cancelled(), "error", clientSafeError(e, "全量建图失败，请稍后重试"));
                emitter.complete();
            } finally {
                WorkspaceContext.clear();
            }
        });
        return emitter;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String workspaceId,
            @RequestParam(name = "all", defaultValue = "false") boolean all) {
        List<Map<String, Object>> list = all
                ? repo.listAll()
                : (workspaceId != null && !workspaceId.isBlank() ? repo.list(workspaceId) : repo.list());
        return ResponseEntity.ok(list);
    }

    /** 当前工作空间「尚未引用」的公共经验（引用选择器列出可引入的经验）。 */
    @GetMapping("/referencable")
    public ResponseEntity<List<Map<String, Object>>> referencable() {
        return ResponseEntity.ok(repo.listReferencable(WorkspaceContext.required()));
    }

    /** 把一批公共经验引用进当前工作空间（已引用的跳过）。请求体：{ experienceIds: [...] }。 */
    @PostMapping("/refs")
    public ResponseEntity<Map<String, Object>> reference(@RequestBody Map<String, Object> body) {
        Object ids = body == null ? null : body.get("experienceIds");
        List<String> list = new java.util.ArrayList<>();
        if (ids instanceof List<?> arr) {
            for (Object o : arr) if (o != null) list.add(String.valueOf(o));
        }
        int added = repo.reference(list);
        return ResponseEntity.ok(Map.of("added", added));
    }

    /** 取消当前工作空间对某经验的引用（不删除经验本体）。 */
    @DeleteMapping("/{id}/ref")
    public ResponseEntity<SuccessCountResponse> unreference(@PathVariable String id) {
        boolean ok = repo.unreference(id);
        return ResponseEntity.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String id) {
        Map<String, Object> exp = repo.findFull(id);
        return exp == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(exp);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody ExperienceCreateRequest req) {
        Map<String, Object> exp = repo.create(req.getTitle(), req.getContent(), req.getTags());
        triggerReindex(exp);
        return ResponseEntity.ok(exp);
    }

    /** 上传文件建经验：抽文本/音频 ASR 作正文，归档原件，保存后自动建向量索引。 */
    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title) throws IOException {
        Map<String, Object> exp = fileService.createFromUpload(file, title);
        triggerReindex(exp);
        return ResponseEntity.ok(exp);
    }

    /**
     * 预览 / 下载经验的原始上传文件（origin=upload 且归档成功时可用）。
     * <p>默认 inline 供浏览器直接预览（PDF / 图片 / 文本）；带 {@code ?download=true} 时作附件下载。
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<InputStreamResource> previewFile(
            @PathVariable String id,
            @RequestParam(name = "download", defaultValue = "false") boolean download) {
        ExperiencePO po = repo.findPoScoped(id);
        if (po == null || po.getStoragePath() == null || po.getStoragePath().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        InputStream in = storage.openStream(po.getStoragePath());
        if (in == null) return ResponseEntity.notFound().build();

        String mime = po.getFileMime() == null || po.getFileMime().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : po.getFileMime();
        String name = po.getFileName() == null || po.getFileName().isBlank() ? id : po.getFileName();
        long size = po.getFileSize() == null ? storage.size(po.getStoragePath()) : po.getFileSize();
        String disposition = (download ? "attachment" : "inline")
                + "; filename*=UTF-8''" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);

        ResponseEntity.BodyBuilder b = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType(mime));
        if (size >= 0) b.contentLength(size);
        return b.body(new InputStreamResource(in));
    }

    /**
     * 把数据源抽取成一条经验（任意类型：关系型库导 DDL、HTTPS 接口导配置+响应样例），
     * 保存后自动建向量索引，便于对话建模时召回。
     * <p>请求体：{ "dataSourceId": "...", "sampleRows": 3 }。sampleRows&gt;0 时（仅库类）附带前 N 行样例数据。
     */
    @PostMapping("/from-ddl")
    public ResponseEntity<Map<String, Object>> fromDdl(@RequestBody Map<String, Object> body) {
        Object idObj = body == null ? null : body.get("dataSourceId");
        String dataSourceId = idObj == null ? null : String.valueOf(idObj);
        int sampleRows = 0;
        Object sr = body == null ? null : body.get("sampleRows");
        if (sr instanceof Number num) sampleRows = num.intValue();
        else if (sr != null) { try { sampleRows = Integer.parseInt(String.valueOf(sr).trim()); } catch (NumberFormatException ignore) {} }

        Map<String, Object> exp = fileService.createFromDataSource(dataSourceId, sampleRows);
        // 「抽取到经验库」是工作空间内的明确动作：直接把生成的经验引用进当前工作空间，
        // 让它立刻出现在该工作空间侧栏（其余公共库新增不自动引用，需手动「引用」）。
        if (exp != null && exp.get("id") != null) {
            try { repo.reference(String.valueOf(exp.get("id"))); }
            catch (Exception e) { log.warn("[experience] DDL 抽取自动引用失败: {}", e.toString()); }
        }
        triggerReindex(exp);
        return ResponseEntity.ok(exp);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id,
                                                      @RequestBody ExperienceUpdateRequest req) {
        Map<String, Object> exp = repo.update(id, req.getTitle(), req.getContent(), req.getTags());
        if (exp == null) return ResponseEntity.notFound().build();
        triggerReindex(exp);
        return ResponseEntity.ok(exp);
    }

    /**
     * 接入一个 web 系统并保存为经验条目（origin=websystem）。请求体：
     * { title?, baseUrl, username?, password?, maxSteps?, readOnly?(默认 true), storageState? }。
     * <p>保存后不立即探索；在该条目上点「探索」(/api/explore/run-saved) 才按此配置运行自动探索。
     */
    @PostMapping("/websystem")
    public ResponseEntity<Map<String, Object>> createWebSystem(@RequestBody Map<String, Object> body) {
        // 规范化入口地址：补全 https://、拦掉非网址（throw → 400），并把规范化结果回填 body 后再组装配置
        String baseUrl = WebUrls.normalizeEntryUrl(WebSystemConfigAssembler.str(body, "baseUrl"));
        body.put("baseUrl", baseUrl);
        String title = WebSystemConfigAssembler.str(body, "title");
        if (title == null || title.isBlank()) title = "「" + baseUrl + "」web 系统";
        Map<String, Object> config = WebSystemConfigAssembler.assemble(body, new LinkedHashMap<>());
        Map<String, Object> exp = repo.createWebSystem(title.trim(), config);
        return ResponseEntity.ok(exp);
    }

    /**
     * 编辑已接入 web 系统的连接配置。密码留空或为遮蔽串（********）时保留原密码；
     * storageState 留空时保留原值。
     */
    @PutMapping("/websystem/{id}")
    public ResponseEntity<Map<String, Object>> updateWebSystem(@PathVariable String id,
                                                               @RequestBody Map<String, Object> body) {
        Map<String, Object> existing = repo.readSourceConfigScoped(id);
        if (existing == null) return ResponseEntity.notFound().build();
        body.put("baseUrl", WebUrls.normalizeEntryUrl(WebSystemConfigAssembler.str(body, "baseUrl")));
        Map<String, Object> config = WebSystemConfigAssembler.assemble(body, existing);
        Map<String, Object> exp = repo.updateWebSystem(id, WebSystemConfigAssembler.str(body, "title"), config);
        if (exp == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(exp);
    }

    /** 移动经验到文件夹：{folderId}（null/空 = 移到根）。 */
    @PutMapping("/{id}/folder")
    public ResponseEntity<SuccessCountResponse> moveToFolder(@PathVariable String id,
                                                             @RequestBody(required = false) Map<String, Object> body) {
        Object v = body == null ? null : body.get("folderId");
        String folderId = v == null ? null : String.valueOf(v);
        boolean ok = repo.moveToFolder(id, folderId);
        return ResponseEntity.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = repo.delete(id);
        // 级联清理归档原件（向量索引随外键级联，原件在对象存储里需手动删）
        if (ok) {
            try { storage.deletePrefix(FILE_PREFIX + id + "/"); }
            catch (Exception e) { log.warn("[experience] 删除归档原件失败 id={}: {}", id, e.toString()); }
        }
        return ResponseEntity.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    /** 手动重建索引（embedding 配置变更后补建用）。返回是否已触发。 */
    @PostMapping("/{id}/reindex")
    public ResponseEntity<Map<String, Object>> reindex(@PathVariable String id) {
        boolean configured = indexService.isConfigured();
        if (configured) indexService.reindexAsync(id);
        return ResponseEntity.ok(Map.of("triggered", configured, "configured", configured));
    }

    @GetMapping("/{id}/index-status")
    public ResponseEntity<Map<String, Object>> indexStatus(@PathVariable String id) {
        return ResponseEntity.ok(indexService.getIndexStatus(id));
    }

    /**
     * 全量补索引经验库（embedding 配置变更后手动触发）。立即返回调度概况，实际索引后台排队进行。
     *
     * @param force true 连已索引的也重建；默认 false 只补未索引的
     */
    @PostMapping("/reindex-all")
    public ResponseEntity<Map<String, Object>> reindexAll(
            @RequestParam(defaultValue = "false") boolean force) {
        return ResponseEntity.ok(indexService.reindexAll(force));
    }

    /** 索引状态汇总：经验总数 + 各 index_status 计数，用于查看补索引进度。 */
    @GetMapping("/index-summary")
    public ResponseEntity<Map<String, Object>> indexSummary() {
        return ResponseEntity.ok(indexService.indexSummary());
    }

    /** 创建/编辑成功后异步重建该条经验的向量索引；未配置 embedding 时静默跳过。 */
    private void triggerReindex(Map<String, Object> exp) {
        Object id = exp == null ? null : exp.get("id");
        if (id != null) indexService.reindexAsync(String.valueOf(id));
    }
}

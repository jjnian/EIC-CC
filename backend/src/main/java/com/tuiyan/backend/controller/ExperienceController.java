package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.model.dto.ExperienceCreateRequest;
import com.tuiyan.backend.model.dto.ExperienceUpdateRequest;
import com.tuiyan.backend.model.dto.SuccessCountResponse;
import com.tuiyan.backend.repository.ExperienceRepository;
import com.tuiyan.backend.service.DataSourceService;
import com.tuiyan.backend.service.ExperienceOntologyService;
import com.tuiyan.backend.service.connector.FileStoredService;
import com.tuiyan.backend.service.connector.file.StoredFileHandler;
import com.tuiyan.backend.service.extraction.AudioTranscriptionService;
import com.tuiyan.backend.service.indexing.ExperienceIndexService;
import com.tuiyan.backend.entity.ExperiencePO;
import com.tuiyan.backend.service.storage.ObjectStorage;
import com.tuiyan.backend.support.FileSniffer;
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
 */
@RestController
@RequestMapping("/api/experiences")
public class ExperienceController {

    private final ExperienceRepository repo;
    private final ExperienceIndexService indexService;
    private final FileStoredService fileStoredService;
    private final DataSourceService dataSourceService;
    private final AudioTranscriptionService audioTranscriptionService;
    private final ExperienceOntologyService experienceOntology;
    private final ObjectStorage storage;
    private final AsyncTaskExecutor taskExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(ExperienceController.class);
    /** 上传原件归档前缀，与数据源 datasource-files/ 平行。 */
    private static final String FILE_PREFIX = "experience-files/";

    public ExperienceController(ExperienceRepository repo,
                                ExperienceIndexService indexService,
                                FileStoredService fileStoredService,
                                DataSourceService dataSourceService,
                                AudioTranscriptionService audioTranscriptionService,
                                ExperienceOntologyService experienceOntology,
                                ObjectStorage storage,
                                @Qualifier("predictionExecutor") AsyncTaskExecutor taskExecutor) {
        this.repo = repo;
        this.indexService = indexService;
        this.fileStoredService = fileStoredService;
        this.dataSourceService = dataSourceService;
        this.audioTranscriptionService = audioTranscriptionService;
        this.experienceOntology = experienceOntology;
        this.storage = storage;
        this.taskExecutor = taskExecutor;
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
                        experienceOntology.extractFromWorkspace(modelOverride, configId, userHint, step);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("nodes", r.payload().path("nodes"));
                payload.put("edges", r.payload().path("edges"));
                payload.put("reply", r.payload().path("reply").asText(""));
                payload.put("salt", r.salt());
                payload.put("sourceCount", r.sourceCount());
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

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String workspaceId,
            @RequestParam(name = "all", defaultValue = "false") boolean all) {
        List<Map<String, Object>> list = all
                ? repo.listAll()
                : (workspaceId != null && !workspaceId.isBlank() ? repo.list(workspaceId) : repo.list());
        return ResponseEntity.ok(list);
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

    /**
     * 上传文件建经验：抽取文件纯文本作正文，文件名（去扩展名）作标题，保存后自动建向量索引。
     * <p>PDF / Word / TXT / MD 走文本抽取；音频走 ASR 转写（转写文本作正文）。
     */
    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件为空"));
        }
        StoredFileHandler.Result extracted = fileStoredService.extractText(file);
        String content = extracted.text() == null ? "" : extracted.text();

        // 音频：归档 handler 不抽文本（meta.audio=true），改走 ASR 转写得到正文
        if (content.isBlank() && Boolean.TRUE.equals(extracted.meta().get("audio"))) {
            if (!audioTranscriptionService.enabled()) {
                return ResponseEntity.badRequest().body(Map.of("error", "音频转写未启用（app.asr.enabled=false）"));
            }
            try {
                AudioTranscriptionService.TranscriptResult tr = audioTranscriptionService.transcribe(
                        file.getBytes(), file.getOriginalFilename(), file.getContentType());
                content = tr.text() == null ? "" : tr.text();
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "音频转写失败：" + e.getMessage()));
            }
        }

        if (content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "未能从文件中抽取到文本内容"));
        }
        String safeName = FileSniffer.sanitizeFilename(file.getOriginalFilename());
        String finalTitle = (title != null && !title.isBlank())
                ? title.trim()
                : stripExtension(file.getOriginalFilename());
        String mime = resolveMime(file.getContentType(), safeName);
        Map<String, Object> exp = repo.createUploaded(finalTitle, content, safeName, mime, file.getSize());

        // 归档原始文件以便预览/下载；存储不可用时降级为「仅文本经验」，不影响主流程
        String id = String.valueOf(exp.get("id"));
        try {
            String key = FILE_PREFIX + id + "/" + safeName;
            storage.putBytes(key, file.getBytes(), mime);
            repo.attachStoragePath(id, key);
            exp.put("hasFile", true);
        } catch (Exception e) {
            log.warn("[experience] 原件归档失败 id={} name={}: {}", id, safeName, e.toString());
        }

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

    /** content-type 兜底：上传头缺失 / 为通用二进制流时按扩展名推断常见可预览类型。 */
    private static String resolveMime(String contentType, String filename) {
        if (contentType != null && !contentType.isBlank()
                && !contentType.equalsIgnoreCase(MediaType.APPLICATION_OCTET_STREAM_VALUE)) {
            return contentType;
        }
        String n = filename == null ? "" : filename.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".md")) return "text/markdown; charset=utf-8";
        if (n.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".doc")) return "application/msword";
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    /**
     * 从数据库数据源导出 DDL 并存为一条经验：抽取 mysql/pgsql 的 CREATE TABLE/VIEW 结构作正文，
     * 保存后自动建向量索引，便于对话建模时召回库表结构。请求体：{ "dataSourceId": "..." }。
     */
    @PostMapping("/from-ddl")
    public ResponseEntity<Map<String, Object>> fromDdl(@RequestBody Map<String, Object> body) {
        Object idObj = body == null ? null : body.get("dataSourceId");
        String dataSourceId = idObj == null ? null : String.valueOf(idObj);
        if (dataSourceId == null || dataSourceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 dataSourceId"));
        }
        DataSourceService.DdlExport export = dataSourceService.exportDdl(dataSourceId);
        String title = "「" + export.sourceName() + "」数据库 DDL";
        String content = "# " + title + "\n\n"
                + "> 库: `" + export.database() + "` · 对象数: " + export.objectCount()
                + " · 由数据源结构内省自动生成\n\n"
                + "```sql\n" + export.ddl() + "\n```\n";
        Map<String, Object> exp = repo.create(title, content, "DDL,schema", "ddl");
        triggerReindex(exp);
        return ResponseEntity.ok(exp);
    }

    /** 去掉文件名扩展名作为经验标题；空名兜底为「未命名文件」。 */
    private static String stripExtension(String filename) {
        if (filename == null || filename.isBlank()) return "未命名文件";
        String name = filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.isBlank() ? "未命名文件" : name;
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
        String baseUrl = WebUrls.normalizeEntryUrl(str(body, "baseUrl"));
        body.put("baseUrl", baseUrl);
        String title = str(body, "title");
        if (title == null || title.isBlank()) title = "「" + baseUrl + "」web 系统";
        Map<String, Object> config = assembleConfig(body, new LinkedHashMap<>());
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
        body.put("baseUrl", WebUrls.normalizeEntryUrl(str(body, "baseUrl")));
        Map<String, Object> config = assembleConfig(body, existing);
        Map<String, Object> exp = repo.updateWebSystem(id, str(body, "title"), config);
        if (exp == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(exp);
    }

    /**
     * 把请求体里的连接字段合成为待存配置：在 existing 基础上覆盖。
     * 密码为空 / 遮蔽串、storageState 为空时沿用 existing 原值（避免编辑时把敏感字段清掉）。
     */
    private Map<String, Object> assembleConfig(Map<String, Object> body, Map<String, Object> existing) {
        Map<String, Object> cfg = new LinkedHashMap<>(existing);
        cfg.put("baseUrl", str(body, "baseUrl"));
        cfg.put("username", str(body, "username"));
        cfg.put("maxSteps", intOr(body, "maxSteps", 15));
        cfg.put("readOnly", !"false".equalsIgnoreCase(str(body, "readOnly"))); // 默认只读
        String pwd = str(body, "password");
        if (pwd != null && !pwd.isBlank() && !"********".equals(pwd)) cfg.put("password", pwd);
        String ss = str(body, "storageState");
        if (ss != null && !ss.isBlank()) cfg.put("storageState", ss);
        return cfg;
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intOr(Map<String, Object> body, String key, int dflt) {
        String s = str(body, key);
        if (s == null || s.isBlank()) return dflt;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return dflt; }
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

    /** 创建/编辑成功后异步重建该条经验的向量索引；未配置 embedding 时静默跳过。 */
    private void triggerReindex(Map<String, Object> exp) {
        Object id = exp == null ? null : exp.get("id");
        if (id != null) indexService.reindexAsync(String.valueOf(id));
    }
}

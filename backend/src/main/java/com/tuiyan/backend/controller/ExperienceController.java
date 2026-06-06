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
import com.tuiyan.backend.support.SsePushUtils;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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
    private final AsyncTaskExecutor taskExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExperienceController(ExperienceRepository repo,
                                ExperienceIndexService indexService,
                                FileStoredService fileStoredService,
                                DataSourceService dataSourceService,
                                AudioTranscriptionService audioTranscriptionService,
                                ExperienceOntologyService experienceOntology,
                                @Qualifier("predictionExecutor") AsyncTaskExecutor taskExecutor) {
        this.repo = repo;
        this.indexService = indexService;
        this.fileStoredService = fileStoredService;
        this.dataSourceService = dataSourceService;
        this.audioTranscriptionService = audioTranscriptionService;
        this.experienceOntology = experienceOntology;
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
        String finalTitle = (title != null && !title.isBlank())
                ? title.trim()
                : stripExtension(file.getOriginalFilename());
        Map<String, Object> exp = repo.create(finalTitle, content, null);
        triggerReindex(exp);
        return ResponseEntity.ok(exp);
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
        Map<String, Object> exp = repo.create(title, content, "DDL,schema");
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

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = repo.delete(id);
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

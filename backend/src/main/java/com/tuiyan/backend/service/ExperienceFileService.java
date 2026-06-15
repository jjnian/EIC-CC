package com.tuiyan.backend.service;

import com.tuiyan.backend.repository.ExperienceRepository;
import com.tuiyan.backend.service.connector.FileStoredService;
import com.tuiyan.backend.service.connector.file.StoredFileHandler;
import com.tuiyan.backend.service.extraction.AudioTranscriptionService;
import com.tuiyan.backend.service.storage.ObjectStorage;
import com.tuiyan.backend.support.FileSniffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 经验条目的「来源构建」服务：把 ExperienceController 里"从文件/数据库 DDL 造一条经验"的业务逻辑
 * （抽文本、音频 ASR 兜底、原件归档、DDL 导出拼正文）从控制器中分离出来。
 * <p>入参非法时统一抛 {@link IllegalArgumentException}（由全局异常处理器映射为 400 {error}）。
 * 不负责向量索引重建，由调用方在拿到结果后触发。
 */
@Service
public class ExperienceFileService {

    private static final Logger log = LoggerFactory.getLogger(ExperienceFileService.class);
    /** 上传原件归档前缀，与数据源 datasource-files/ 平行。 */
    private static final String FILE_PREFIX = "experience-files/";

    private final ExperienceRepository repo;
    private final FileStoredService fileStoredService;
    private final AudioTranscriptionService audioTranscriptionService;
    private final ObjectStorage storage;
    private final DataSourceService dataSourceService;

    public ExperienceFileService(ExperienceRepository repo,
                                 FileStoredService fileStoredService,
                                 AudioTranscriptionService audioTranscriptionService,
                                 ObjectStorage storage,
                                 DataSourceService dataSourceService) {
        this.repo = repo;
        this.fileStoredService = fileStoredService;
        this.audioTranscriptionService = audioTranscriptionService;
        this.storage = storage;
        this.dataSourceService = dataSourceService;
    }

    /**
     * 上传文件建经验：抽取文件纯文本作正文，文件名（去扩展名）作标题，归档原件。
     * <p>PDF / Word / TXT / MD 走文本抽取；音频走 ASR 转写（转写文本作正文）。
     */
    public Map<String, Object> createFromUpload(MultipartFile file, String title) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        StoredFileHandler.Result extracted = fileStoredService.extractText(file);
        String content = extracted.text() == null ? "" : extracted.text();

        // 音频：归档 handler 不抽文本（meta.audio=true），改走 ASR 转写得到正文
        if (content.isBlank() && Boolean.TRUE.equals(extracted.meta().get("audio"))) {
            if (!audioTranscriptionService.enabled()) {
                throw new IllegalArgumentException("音频转写未启用（app.asr.enabled=false）");
            }
            try {
                AudioTranscriptionService.TranscriptResult tr = audioTranscriptionService.transcribe(
                        file.getBytes(), file.getOriginalFilename(), file.getContentType());
                content = tr.text() == null ? "" : tr.text();
            } catch (Exception e) {
                throw new IllegalArgumentException("音频转写失败：" + e.getMessage());
            }
        }

        if (content.isBlank()) {
            throw new IllegalArgumentException("未能从文件中抽取到文本内容");
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
        return exp;
    }

    /**
     * 从数据库数据源导出 DDL 并存为一条经验：抽取 mysql/pgsql 的 CREATE TABLE/VIEW 结构作正文。
     * <p>sampleRows&gt;0 时为每张基表附带前 N 行样例数据；同一数据源重复导出走 upsert。
     */
    public Map<String, Object> createFromDdl(String dataSourceId, int sampleRows) {
        if (dataSourceId == null || dataSourceId.isBlank()) {
            throw new IllegalArgumentException("缺少 dataSourceId");
        }
        DataSourceService.DdlExport export = dataSourceService.exportDdl(dataSourceId, sampleRows);
        String title = "「" + export.sourceName() + "」数据库 DDL";
        String content = "# " + title + "\n\n"
                + "> 库: `" + export.database() + "` · 对象数: " + export.objectCount()
                + (export.withSamples() ? " · 含样例数据" : "")
                + " · 由数据源结构内省自动生成\n\n"
                + "```sql\n" + export.ddl() + "\n```\n";
        return repo.upsertDdl(dataSourceId, title, content, "DDL,schema");
    }

    /** content-type 兜底：上传头缺失 / 为通用二进制流时按扩展名推断常见可预览类型。 */
    static String resolveMime(String contentType, String filename) {
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

    /** 去掉文件名扩展名作为经验标题；空名兜底为「未命名文件」。 */
    static String stripExtension(String filename) {
        if (filename == null || filename.isBlank()) return "未命名文件";
        String name = filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.isBlank() ? "未命名文件" : name;
    }
}

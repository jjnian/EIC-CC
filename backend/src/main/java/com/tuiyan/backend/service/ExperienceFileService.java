package com.tuiyan.backend.service;

import com.tuiyan.backend.repository.ExperienceRepository;
import com.tuiyan.backend.service.connector.FileStoredService;
import com.tuiyan.backend.service.connector.file.StoredFileHandler;
import com.tuiyan.backend.service.extraction.AudioTranscriptionService;
import com.tuiyan.backend.service.extraction.ImageRecognitionService;
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
    private final ImageRecognitionService imageRecognitionService;
    private final ObjectStorage storage;
    private final DataSourceService dataSourceService;

    public ExperienceFileService(ExperienceRepository repo,
                                 FileStoredService fileStoredService,
                                 AudioTranscriptionService audioTranscriptionService,
                                 ImageRecognitionService imageRecognitionService,
                                 ObjectStorage storage,
                                 DataSourceService dataSourceService) {
        this.repo = repo;
        this.fileStoredService = fileStoredService;
        this.audioTranscriptionService = audioTranscriptionService;
        this.imageRecognitionService = imageRecognitionService;
        this.storage = storage;
        this.dataSourceService = dataSourceService;
    }

    /**
     * 上传文件建经验：抽取文件纯文本作正文，文件名（去扩展名）作标题，归档原件。
     * <p>PDF / Word / TXT / MD 走文本抽取；音频走 ASR 转写；图片走视觉识别（OCR + 关键信息），均以识别文本作正文。
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

        // 视频：归档 handler 不抽文本（meta.video=true），抽音轨（ffmpeg，装了则压缩、没装则原样直发）→ ASR 转写
        if (content.isBlank() && Boolean.TRUE.equals(extracted.meta().get("video"))) {
            if (!audioTranscriptionService.enabled()) {
                throw new IllegalArgumentException("音频转写未启用（app.asr.enabled=false），无法转写视频");
            }
            try {
                byte[] audio;
                String audioName;
                String audioMime;
                if (com.tuiyan.backend.service.extraction.VideoAudioExtractor.ffmpegAvailable()) {
                    audio = com.tuiyan.backend.service.extraction.VideoAudioExtractor.extractAudio(
                            file.getBytes(), file.getOriginalFilename());
                    audioName = file.getOriginalFilename() + ".mp3";
                    audioMime = "audio/mpeg";
                } else if (file.getSize() <= audioTranscriptionService.maxBytes()) {
                    audio = file.getBytes();
                    audioName = file.getOriginalFilename();
                    audioMime = file.getContentType();
                } else {
                    throw new IllegalArgumentException("视频超过转写大小上限 "
                            + (audioTranscriptionService.maxBytes() / (1024 * 1024))
                            + " MB 且未安装 ffmpeg（无法抽音轨压缩），请安装 ffmpeg 或先转成音频");
                }
                if (audio.length > audioTranscriptionService.maxBytes()) {
                    throw new IllegalArgumentException("抽出的音轨仍超过转写大小上限 "
                            + (audioTranscriptionService.maxBytes() / (1024 * 1024)) + " MB，请剪辑分段后重试");
                }
                AudioTranscriptionService.TranscriptResult tr =
                        audioTranscriptionService.transcribe(audio, audioName, audioMime);
                content = tr.text() == null ? "" : tr.text();
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("视频转写失败：" + e.getMessage());
            }
        }

        // 图片：归档 handler 不抽文本（meta.image=true），改走视觉识别（OCR + 关键信息）得到正文
        if (content.isBlank() && Boolean.TRUE.equals(extracted.meta().get("image"))) {
            try {
                content = imageRecognitionService.recognize(
                        file.getBytes(), file.getOriginalFilename(), file.getContentType());
            } catch (Exception e) {
                throw new IllegalArgumentException("图片识别失败：" + e.getMessage());
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
     * 把「任意类型」的数据源抽取成一条经验：关系型库导 DDL（sampleRows&gt;0 附样例数据），
     * HTTPS 接口导请求配置 + 最近响应样例。文档形态与正文由 {@link DataSourceService#exportSourceDoc} 决定，
     * 本方法只负责按数据源 id upsert（同一数据源重复抽取覆盖同一条经验）。
     */
    public Map<String, Object> createFromDataSource(String dataSourceId, int sampleRows) {
        if (dataSourceId == null || dataSourceId.isBlank()) {
            throw new IllegalArgumentException("缺少 dataSourceId");
        }
        DataSourceService.SourceDocExport doc = dataSourceService.exportSourceDoc(dataSourceId, sampleRows);
        return repo.upsertDdl(dataSourceId, doc.title(), doc.content(), doc.tags());
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
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".avi")) return "video/x-msvideo";
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

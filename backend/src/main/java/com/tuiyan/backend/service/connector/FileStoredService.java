package com.tuiyan.backend.service.connector;

import com.tuiyan.backend.config.AppPaths;
import com.tuiyan.backend.support.FileSniffer;
import com.tuiyan.backend.support.PdfTextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件数据源服务：PDF/TXT/MD 落盘到 ~/.tuiyan/datasource-files/<id>/ 下，
 * 同步抽出纯文本旁挂 .txt。
 */
@Service
public class FileStoredService {

    private static final Logger log = LoggerFactory.getLogger(FileStoredService.class);
    public static final long PDF_LIMIT_BYTES = 12L * 1024 * 1024;
    public static final long TEXT_LIMIT_BYTES = 4L * 1024 * 1024;
    public static final int TEXT_CHAR_BUDGET = 200_000;

    private final AppPaths paths;

    public FileStoredService(AppPaths paths) { this.paths = paths; }

    /**
     * 接收上传，落盘并抽文本。
     * @return config_json 的内容（storagePath / extractedTextPath / chars / pages）
     */
    public Map<String, Object> ingest(String dataSourceId, MultipartFile mf) throws IOException {
        String safe = FileSniffer.sanitizeFilename(mf.getOriginalFilename());
        String lname = safe.toLowerCase();
        byte[] head = FileSniffer.readHead(mf, 12);

        boolean isPdf = FileSniffer.isPdfMagic(head) || lname.endsWith(".pdf");
        boolean isTxt = lname.endsWith(".txt") || lname.endsWith(".md");
        if (!isPdf && !isTxt) {
            throw new IllegalArgumentException("仅支持 PDF / TXT / MD 文件");
        }
        long size = mf.getSize();
        if (isPdf && size > PDF_LIMIT_BYTES) {
            throw new IllegalArgumentException("PDF 超过 " + (PDF_LIMIT_BYTES / 1024 / 1024) + " MB 限制");
        }
        if (isTxt && size > TEXT_LIMIT_BYTES) {
            throw new IllegalArgumentException("TXT/MD 超过 " + (TEXT_LIMIT_BYTES / 1024 / 1024) + " MB 限制");
        }

        // 子目录：datasource-files/<id>/
        File baseDir = paths.datasourceFilesDir();
        File targetDir = new File(baseDir, dataSourceId);
        Files.createDirectories(targetDir.toPath());

        File rawFile = new File(targetDir, safe);
        try (var in = mf.getInputStream()) {
            Files.copy(in, rawFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        String text;
        int pages = 0;
        if (isPdf) {
            try (PDDocument doc = Loader.loadPDF(rawFile)) {
                pages = doc.getNumberOfPages();
                text = PdfTextExtractor.extractText(doc);
            } catch (Exception e) {
                log.warn("PDF 抽文本失败: {}", e.toString());
                text = "";
            }
        } else {
            text = Files.readString(rawFile.toPath(), StandardCharsets.UTF_8);
        }
        if (text != null && text.length() > TEXT_CHAR_BUDGET) {
            text = text.substring(0, TEXT_CHAR_BUDGET) + "\n[…truncated…]";
        }
        int chars = text == null ? 0 : text.length();

        // 旁挂 .txt
        File txtFile = new File(targetDir, safe + ".txt");
        Files.writeString(txtFile.toPath(), text == null ? "" : text, StandardCharsets.UTF_8);

        // 相对路径写入 config_json（防止根目录漂移）
        String rel = "datasource-files/" + dataSourceId + "/" + safe;
        String relTxt = "datasource-files/" + dataSourceId + "/" + safe + ".txt";

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("storagePath", rel);
        cfg.put("extractedTextPath", relTxt);
        cfg.put("chars", chars);
        cfg.put("pages", pages);
        cfg.put("originalName", safe);
        cfg.put("sizeBytes", size);
        return cfg;
    }

    /** 读取已抽取的文本片段（按字符偏移 + 长度）。 */
    public String readText(Map<String, Object> cfg, int offset, int length) throws IOException {
        String rel = String.valueOf(cfg.getOrDefault("extractedTextPath", ""));
        if (rel.isBlank()) return "";
        File f = new File(paths.rootDir(), rel);
        if (!f.exists()) return "";
        String all = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        int start = Math.max(0, Math.min(offset, all.length()));
        int end = Math.min(all.length(), start + Math.max(1, length));
        return all.substring(start, end);
    }

    /** 取原文件 File 对象供下载使用。文件不存在时返回 null。 */
    public File originalFile(Map<String, Object> cfg) {
        String rel = String.valueOf(cfg.getOrDefault("storagePath", ""));
        if (rel.isBlank()) return null;
        File f = new File(paths.rootDir(), rel);
        return f.exists() ? f : null;
    }

    /** 删除数据源时级联清理：删整个 datasource-files/<id>/ 目录。 */
    public void deleteFiles(String dataSourceId) {
        File dir = new File(paths.datasourceFilesDir(), dataSourceId);
        if (!dir.exists()) return;
        try (var stream = Files.walk(dir.toPath())) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                  .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException e) {
            log.warn("clean datasource files failed: {}", e.toString());
        }
    }
}

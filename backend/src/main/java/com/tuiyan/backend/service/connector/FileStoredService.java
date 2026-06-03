package com.tuiyan.backend.service.connector;

import com.tuiyan.backend.service.storage.ObjectStorageService;
import com.tuiyan.backend.service.storage.StoredObject;
import com.tuiyan.backend.support.DocxTextExtractor;
import com.tuiyan.backend.support.FileSniffer;
import com.tuiyan.backend.support.PdfTextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 文件数据源服务：PDF / Word(.docx) / TXT / MD / 音频等原文件统一上传到 MinIO 桶
 * {@code datasource-files/<id>/} 路径下，并同步抽出纯文本作为旁挂 {@code .txt} 对象。
 * <p>音频文件只落桶存储、不抽文本（抽取文本为空），用于归档与下载。
 */
@Service
public class FileStoredService {

    private static final Logger log = LoggerFactory.getLogger(FileStoredService.class);
    public static final long PDF_LIMIT_BYTES = 12L * 1024 * 1024;
    public static final long DOCX_LIMIT_BYTES = 12L * 1024 * 1024;
    public static final long TEXT_LIMIT_BYTES = 4L * 1024 * 1024;
    public static final long AUDIO_LIMIT_BYTES = 50L * 1024 * 1024;
    public static final int TEXT_CHAR_BUDGET = 200_000;

    /** 受支持的音频扩展名（小写，含点）。 */
    private static final Set<String> AUDIO_EXTS = Set.of(
            ".mp3", ".wav", ".m4a", ".flac", ".aac", ".ogg", ".opus", ".wma", ".amr");

    private static final String PREFIX = "datasource-files/";

    private final ObjectStorageService storage;

    public FileStoredService(ObjectStorageService storage) { this.storage = storage; }

    /**
     * 接收上传，落到 MinIO 并抽文本。
     * @return config_json 的内容（storagePath / extractedTextPath / chars / pages ...）
     */
    public Map<String, Object> ingest(String dataSourceId, MultipartFile mf) throws IOException {
        String safe = FileSniffer.sanitizeFilename(mf.getOriginalFilename());
        String lname = safe.toLowerCase();
        byte[] head = FileSniffer.readHead(mf, 12);

        boolean isPdf = FileSniffer.isPdfMagic(head) || lname.endsWith(".pdf");
        // .docx 是 ZIP 容器：扩展名命中即接收，损坏/伪装文件会在抽取时优雅降级为空文本
        boolean isDocx = lname.endsWith(".docx");
        boolean isTxt = lname.endsWith(".txt") || lname.endsWith(".md");
        boolean isAudio = AUDIO_EXTS.stream().anyMatch(lname::endsWith);
        if (!isPdf && !isDocx && !isTxt && !isAudio) {
            throw new IllegalArgumentException("仅支持 PDF / Word(.docx) / TXT / MD / 音频文件");
        }
        long size = mf.getSize();
        if (isPdf && size > PDF_LIMIT_BYTES) {
            throw new IllegalArgumentException("PDF 超过 " + (PDF_LIMIT_BYTES / 1024 / 1024) + " MB 限制");
        }
        if (isDocx && size > DOCX_LIMIT_BYTES) {
            throw new IllegalArgumentException("Word 文档超过 " + (DOCX_LIMIT_BYTES / 1024 / 1024) + " MB 限制");
        }
        if (isTxt && size > TEXT_LIMIT_BYTES) {
            throw new IllegalArgumentException("TXT/MD 超过 " + (TEXT_LIMIT_BYTES / 1024 / 1024) + " MB 限制");
        }
        if (isAudio && size > AUDIO_LIMIT_BYTES) {
            throw new IllegalArgumentException("音频文件超过 " + (AUDIO_LIMIT_BYTES / 1024 / 1024) + " MB 限制");
        }

        byte[] bytes = mf.getBytes();

        // 原文件落桶：datasource-files/<id>/<safe>
        String rawKey = PREFIX + dataSourceId + "/" + safe;
        storage.putBytes(rawKey, bytes, mf.getContentType());

        String text;
        int pages = 0;
        int paragraphs = 0;
        int tables = 0;
        if (isAudio) {
            // 音频不抽文本，仅归档
            text = "";
        } else if (isPdf) {
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                pages = doc.getNumberOfPages();
                text = PdfTextExtractor.extractText(doc);
            } catch (Exception e) {
                log.warn("PDF 抽文本失败: {}", e.toString());
                text = "";
            }
        } else if (isDocx) {
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                DocxTextExtractor.Result r = DocxTextExtractor.extract(in);
                text = r.text;
                paragraphs = r.paragraphs;
                tables = r.tables;
            } catch (Exception e) {
                log.warn("DOCX 抽文本失败: {}", e.toString());
                text = "";
            }
        } else {
            text = decodeLenient(bytes);
        }
        if (text != null && text.length() > TEXT_CHAR_BUDGET) {
            text = text.substring(0, TEXT_CHAR_BUDGET) + "\n[…truncated…]";
        }
        int chars = text == null ? 0 : text.length();

        // 旁挂 .txt 也落桶
        String txtKey = PREFIX + dataSourceId + "/" + safe + ".txt";
        storage.putBytes(txtKey, (text == null ? "" : text).getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=utf-8");

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("storagePath", rawKey);
        cfg.put("extractedTextPath", txtKey);
        cfg.put("chars", chars);
        cfg.put("pages", pages);
        // Word 文档没有"页"的概念，用段落 / 表格数量代替，给前端概览展示
        if (paragraphs > 0) cfg.put("paragraphs", paragraphs);
        if (tables > 0) cfg.put("tables", tables);
        if (isAudio) cfg.put("audio", true);
        cfg.put("originalName", safe);
        cfg.put("sizeBytes", size);
        return cfg;
    }

    /**
     * 宽容解码文本：优先按 UTF-8 严格解码；遇到非 UTF-8 字节（常见于
     * Windows 下 GBK/GB18030 编码的中文 .txt）则回退到 GB18030，
     * 仍失败则用替换式 UTF-8 解码兜底，避免上传直接 500 失败。
     */
    static String decodeLenient(byte[] bytes) {
        for (Charset cs : new Charset[]{ StandardCharsets.UTF_8, Charset.forName("GB18030") }) {
            try {
                CharsetDecoder dec = cs.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                return dec.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException ignored) {
                // 尝试下一个编码
            }
        }
        // 兜底：UTF-8 替换式解码，乱码也好过整单失败
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 读取已抽取的文本片段（按字符偏移 + 长度）。 */
    public String readText(Map<String, Object> cfg, int offset, int length) {
        String txtKey = stringValue(cfg.get("extractedTextPath"));
        String all = "";
        if (!txtKey.isBlank()) {
            byte[] b = storage.getBytes(txtKey);
            if (b != null) all = new String(b, StandardCharsets.UTF_8);
        }
        if (all.isBlank()) {
            String rawKey = stringValue(cfg.get("storagePath"));
            if (!rawKey.isBlank()) {
                byte[] b = storage.getBytes(rawKey);
                if (b != null) all = decodeLenient(b);
            }
        }
        return sliceText(all, offset, length);
    }

    /** 取原文件句柄（流 + 大小 + 文件名）供下载使用。对象不存在时返回 null。 */
    public StoredObject originalObject(Map<String, Object> cfg) {
        String key = stringValue(cfg.get("storagePath"));
        if (key.isBlank()) return null;
        long size = storage.size(key);
        if (size < 0) return null;
        InputStream in = storage.openStream(key);
        if (in == null) return null;
        String name = stringValue(cfg.get("originalName"));
        if (name.isBlank()) name = key.substring(key.lastIndexOf('/') + 1);
        return new StoredObject(in, size, "application/octet-stream", name);
    }

    private static String sliceText(String all, int offset, int length) {
        if (all == null || all.isEmpty()) return "";
        int start = Math.max(0, Math.min(offset, all.length()));
        int end = Math.min(all.length(), start + Math.max(1, length));
        return all.substring(start, end);
    }

    private static String stringValue(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 删除数据源时级联清理：删整个 datasource-files/<id>/ 前缀下的对象。 */
    public void deleteFiles(String dataSourceId) {
        storage.deletePrefix(PREFIX + dataSourceId + "/");
    }
}

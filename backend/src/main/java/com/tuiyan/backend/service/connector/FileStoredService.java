package com.tuiyan.backend.service.connector;

import com.tuiyan.backend.service.connector.file.StoredFileHandler;
import com.tuiyan.backend.service.storage.ObjectStorage;
import com.tuiyan.backend.service.storage.StoredObject;
import com.tuiyan.backend.support.FileSniffer;
import com.tuiyan.backend.support.TextDecoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文件数据源编排：把原文件上传到对象存储 {@code datasource-files/<id>/} 前缀下，
 * 并同步抽出纯文本作为旁挂 {@code .txt} 对象。
 * <p>具体「存到哪」由 {@link ObjectStorage} 决定，「某类型怎么抽文本」由
 * {@link StoredFileHandler} 决定；本类只负责串联，不感知 MinIO 也不写死文件类型——
 * 新增格式或更换存储后端都无需改动本类。
 */
@Service
public class FileStoredService {

    /** 抽取文本总字符预算，超出截断（防止超大文件撑爆 config / 前端）。 */
    public static final int TEXT_CHAR_BUDGET = 200_000;
    private static final String PREFIX = "datasource-files/";

    private final ObjectStorage storage;
    private final List<StoredFileHandler> handlers;

    public FileStoredService(ObjectStorage storage, List<StoredFileHandler> handlers) {
        this.storage = storage;
        this.handlers = handlers;
    }

    /**
     * 仅抽取上传文件的纯文本（含截断），不落对象存储。
     * <p>供经验库「上传文件建经验」复用：经验只需正文文本，不需要归档原文件，
     * 因此跳过对象存储，直接走 handler 抽取。文件类型/大小校验与归档路径一致。
     * @return 纯文本（已按 {@link #TEXT_CHAR_BUDGET} 截断）+ 元信息（pages / paragraphs ...）
     */
    public StoredFileHandler.Result extractText(MultipartFile mf) throws IOException {
        String safe = FileSniffer.sanitizeFilename(mf.getOriginalFilename());
        String lname = safe.toLowerCase();
        byte[] head = FileSniffer.readHead(mf, 12);

        StoredFileHandler handler = handlers.stream()
                .filter(h -> h.supports(lname, head))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("仅支持 " + supportedLabels() + " 文件"));

        if (mf.getSize() > handler.maxBytes()) {
            throw new IllegalArgumentException(
                    handler.label() + " 超过 " + (handler.maxBytes() / 1024 / 1024) + " MB 限制");
        }

        StoredFileHandler.Result extracted = handler.extract(mf.getBytes());
        String text = extracted.text() == null ? "" : extracted.text();
        if (text.length() > TEXT_CHAR_BUDGET) {
            text = text.substring(0, TEXT_CHAR_BUDGET) + "\n[…truncated…]";
        }
        return StoredFileHandler.Result.of(text, extracted.meta());
    }

    /** 拼接所有受支持类型名，用于「仅支持 …」错误提示。 */
    private String supportedLabels() {
        return handlers.stream().map(StoredFileHandler::label)
                .distinct().collect(Collectors.joining(" / "));
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
                if (b != null) all = TextDecoder.lenient(b);
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

    /** 删除数据源时级联清理：删整个 datasource-files/<id>/ 前缀下的对象。 */
    public void deleteFiles(String dataSourceId) {
        storage.deletePrefix(PREFIX + dataSourceId + "/");
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
}

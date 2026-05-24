package com.tuiyan.backend.service;

import com.tuiyan.backend.support.DocxTextExtractor;
import com.tuiyan.backend.support.FileSniffer;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量 DOCX 文本抽取：仅返回纯文本，不走 LLM。供对话框上传场景使用。
 * <p>从 DocumentTextController 下沉过来，把文件嗅探、体积校验、字符截断等业务规则集中到 service 层。
 */
@Service
public class DocxExtractionService {

    private static final long DOCX_FILE_BYTES_LIMIT = 12L * 1024 * 1024;
    private static final int  DOCX_TEXT_CHAR_BUDGET = 100_000;

    /**
     * 抽取 DOCX 文本。
     * <p>校验顺序：非空 → 体积 → zip 签名（DOCX 本质是 zip）→ 走 POI 解析 → 字符上限截断。
     * 所有校验失败抛 {@link IllegalArgumentException}，由 GlobalExceptionHandler 映射为 400。
     */
    public Map<String, Object> extractText(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("未提供文件");
        }
        String name = FileSniffer.sanitizeFilename(file.getOriginalFilename());
        long size = file.getSize();
        if (size > DOCX_FILE_BYTES_LIMIT) {
            throw new IllegalArgumentException("DOCX 超过 " + (DOCX_FILE_BYTES_LIMIT / (1024 * 1024)) + " MB 限制");
        }
        byte[] head = FileSniffer.readHead(file, 8);
        if (!FileSniffer.isZipMagic(head)) {
            throw new IllegalArgumentException("文件签名不是 DOCX(zip),无法解析");
        }

        DocxTextExtractor.Result r;
        try (InputStream in = file.getInputStream()) {
            r = DocxTextExtractor.extract(in);
        }
        String text = r.text == null ? "" : r.text;
        int rawChars = text.length();
        boolean truncated = false;
        if (text.length() > DOCX_TEXT_CHAR_BUDGET) {
            text = text.substring(0, DOCX_TEXT_CHAR_BUDGET) + "\n[…truncated…]";
            truncated = true;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("text", text);
        out.put("chars", rawChars);
        out.put("paragraphs", r.paragraphs);
        out.put("tables", r.tables);
        out.put("truncated", truncated);
        return out;
    }
}

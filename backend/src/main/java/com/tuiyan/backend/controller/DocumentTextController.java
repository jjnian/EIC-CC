package com.tuiyan.backend.controller;

import com.tuiyan.backend.support.DocxTextExtractor;
import com.tuiyan.backend.support.FileSniffer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量文档抽取:仅返回纯文本,不走 LLM。供对话框上传场景使用,
 * 便于把 DOCX 内容拼到 chat prompt 里(像文本附件一样)。
 */
@RestController
@RequestMapping("/api/extract")
public class DocumentTextController {

    private static final long DOCX_FILE_BYTES_LIMIT = 12L * 1024 * 1024;
    private static final int  DOCX_TEXT_CHAR_BUDGET = 100_000;

    @PostMapping(value = "/docx-text", consumes = {"multipart/form-data"})
    public ResponseEntity<?> extractDocxText(@RequestParam("file") MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "未提供文件"));
            }
            String name = FileSniffer.sanitizeFilename(file.getOriginalFilename());
            long size = file.getSize();
            if (size > DOCX_FILE_BYTES_LIMIT) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "DOCX 超过 " + (DOCX_FILE_BYTES_LIMIT / (1024 * 1024)) + " MB 限制"));
            }
            byte[] head = FileSniffer.readHead(file, 8);
            if (!FileSniffer.isZipMagic(head)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "文件签名不是 DOCX(zip),无法解析"));
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
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(Map.of("error", "DOCX 解析失败:" + ex.getMessage()));
        }
    }
}

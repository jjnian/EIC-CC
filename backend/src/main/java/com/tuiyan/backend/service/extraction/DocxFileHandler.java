package com.tuiyan.backend.service.extraction;

import com.tuiyan.backend.support.DocxTextExtractor;
import com.tuiyan.backend.support.FileSniffer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

/** DOCX 处理：用 {@link DocxTextExtractor} 抽段落 + 表格，超过预算截断。 */
@Component
@Order(20)
public class DocxFileHandler implements SourceFileHandler {

    private static final int  TEXT_CHAR_BUDGET = 60_000;
    private static final long FILE_BYTES_LIMIT = 12L * 1024 * 1024;

    @Override
    public boolean supports(FileProbe probe) {
        // DOCX 是 ZIP 容器，必须 ZIP 签名 + 扩展名 / content-type 同时命中才认
        if (!FileSniffer.isZipMagic(probe.head())) return false;
        String lname = probe.lowerName();
        String lct = probe.lowerContentType();
        return lname.endsWith(".docx")
                || lct.contains("wordprocessingml")
                || lct.contains("officedocument");
    }

    @Override
    public void handle(UploadedFile f, String safeName, ExtractionContext ctx, Map<String, Object> meta) throws IOException {
        long size = f.size();
        if (size > FILE_BYTES_LIMIT) {
            throw new IllegalArgumentException(
                    "DOCX " + safeName + " 超过 " + (FILE_BYTES_LIMIT / (1024 * 1024)) + " MB 限制");
        }
        DocxTextExtractor.Result r;
        try (var in = new ByteArrayInputStream(f.bytes())) {
            r = DocxTextExtractor.extract(in);
        }
        String text = r.text;
        int rawChars = text == null ? 0 : text.length();
        if (text != null && text.length() > TEXT_CHAR_BUDGET) {
            text = text.substring(0, TEXT_CHAR_BUDGET) + "\n[…truncated…]";
            meta.put("truncated", true);
        }
        meta.put("type", "docx");
        meta.put("chars", rawChars);
        meta.put("paragraphs", r.paragraphs);
        meta.put("tables", r.tables);
        ctx.appendSection("# 文件 " + safeName, text);
    }
}

package com.tuiyan.backend.service.connector.file;

import com.tuiyan.backend.support.DocxTextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Word(.docx) 落桶处理：抽段落 + 表格文本，元信息记录段落 / 表格数。
 * <p>.docx 是 ZIP 容器，仅凭 magic 难与普通 zip 区分，故只按扩展名命中；
 * 损坏 / 伪装文件在抽取时优雅降级为空文本。
 */
@Component
@Order(20)
public class DocxStoredHandler implements StoredFileHandler {

    private static final Logger log = LoggerFactory.getLogger(DocxStoredHandler.class);
    private static final long LIMIT_BYTES = 12L * 1024 * 1024;

    @Override
    public boolean supports(String lowerFilename, byte[] head) {
        return lowerFilename.endsWith(".docx");
    }

    @Override
    public long maxBytes() { return LIMIT_BYTES; }

    @Override
    public String label() { return "Word(.docx)"; }

    @Override
    public Result extract(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            DocxTextExtractor.Result r = DocxTextExtractor.extract(in);
            Map<String, Object> meta = new LinkedHashMap<>();
            // Word 没有「页」的概念，用段落 / 表格数量代替，给前端概览展示
            if (r.paragraphs > 0) meta.put("paragraphs", r.paragraphs);
            if (r.tables > 0) meta.put("tables", r.tables);
            return Result.of(r.text, meta);
        } catch (Exception e) {
            log.warn("DOCX 抽文本失败: {}", e.toString());
            return Result.of("");
        }
    }
}

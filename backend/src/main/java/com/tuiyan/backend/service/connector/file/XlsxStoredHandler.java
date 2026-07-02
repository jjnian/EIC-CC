package com.tuiyan.backend.service.connector.file;

import com.tuiyan.backend.support.XlsxTextExtractor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Excel（XLSX/XLS）落桶处理：逐 Sheet 抽成表格文本作经验正文，原件归档可下载。
 * <p>{@code @Order(25)} 排在 DOCX(20) 之后、文本(30) 之前。
 */
@Component
@Order(25)
public class XlsxStoredHandler implements StoredFileHandler {

    private static final long LIMIT_BYTES = 30L * 1024 * 1024;

    @Override
    public boolean supports(String lowerFilename, byte[] head) {
        return lowerFilename.endsWith(".xlsx") || lowerFilename.endsWith(".xls")
                || lowerFilename.endsWith(".xlsm");
    }

    @Override
    public long maxBytes() { return LIMIT_BYTES; }

    @Override
    public String label() { return "Excel"; }

    @Override
    public Result extract(byte[] bytes) {
        try {
            XlsxTextExtractor.Result r = XlsxTextExtractor.extract(bytes);
            return Result.of(r.text(), Map.of("sheets", r.sheets(), "rows", r.rows()));
        } catch (IOException e) {
            // 接口不声明受检异常：包成 unchecked，由上层统一映射为 400
            throw new UncheckedIOException(e);
        }
    }
}

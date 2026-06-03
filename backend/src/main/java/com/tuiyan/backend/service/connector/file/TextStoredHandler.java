package com.tuiyan.backend.service.connector.file;

import com.tuiyan.backend.support.TextDecoder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 纯文本（.txt / .md）落桶处理：宽容解码为文本，无额外元信息。 */
@Component
@Order(30)
public class TextStoredHandler implements StoredFileHandler {

    private static final long LIMIT_BYTES = 4L * 1024 * 1024;

    @Override
    public boolean supports(String lowerFilename, byte[] head) {
        return lowerFilename.endsWith(".txt") || lowerFilename.endsWith(".md");
    }

    @Override
    public long maxBytes() { return LIMIT_BYTES; }

    @Override
    public String label() { return "TXT/MD"; }

    @Override
    public Result extract(byte[] bytes) {
        return Result.of(TextDecoder.lenient(bytes));
    }
}

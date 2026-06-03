package com.tuiyan.backend.service.connector.file;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 音频文件落桶处理：仅归档存储、不抽文本（无内置语音转写），元信息标记 {@code audio=true}。
 * <p>排在最后（{@code @Order} 最大）兜底匹配音频扩展名。
 */
@Component
@Order(40)
public class AudioStoredHandler implements StoredFileHandler {

    private static final long LIMIT_BYTES = 50L * 1024 * 1024;

    /** 受支持的音频扩展名（小写，含点）。 */
    private static final Set<String> EXTS = Set.of(
            ".mp3", ".wav", ".m4a", ".flac", ".aac", ".ogg", ".opus", ".wma", ".amr");

    @Override
    public boolean supports(String lowerFilename, byte[] head) {
        return EXTS.stream().anyMatch(lowerFilename::endsWith);
    }

    @Override
    public long maxBytes() { return LIMIT_BYTES; }

    @Override
    public String label() { return "音频"; }

    @Override
    public Result extract(byte[] bytes) {
        // 音频不抽文本，仅归档
        return Result.of("", Map.of("audio", true));
    }
}

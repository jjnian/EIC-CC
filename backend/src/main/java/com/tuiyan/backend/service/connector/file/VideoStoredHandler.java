package com.tuiyan.backend.service.connector.file;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 视频文件落桶处理：仅归档存储、不在此抽文本，元信息标记 {@code video=true}；
 * 上层（ExperienceFileService）据此走「抽音轨 → ASR 转写」得到正文。
 */
@Component
@Order(45)
public class VideoStoredHandler implements StoredFileHandler {

    // 与 multipart 上限（默认 200MB，UPLOAD_MAX_FILE_SIZE）对齐
    private static final long LIMIT_BYTES = 200L * 1024 * 1024;

    /** 受支持的视频扩展名（小写，含点）。.webm 归音频 handler（Whisper 原生支持直发）。 */
    private static final Set<String> EXTS = Set.of(
            ".mp4", ".m4v", ".mov", ".mkv", ".avi", ".mpg", ".mpeg", ".wmv", ".flv");

    @Override
    public boolean supports(String lowerFilename, byte[] head) {
        return EXTS.stream().anyMatch(lowerFilename::endsWith);
    }

    @Override
    public long maxBytes() { return LIMIT_BYTES; }

    @Override
    public String label() { return "视频"; }

    @Override
    public Result extract(byte[] bytes) {
        // 视频不在此抽文本，仅归档；由上层抽音轨转写
        return Result.of("", Map.of("video", true));
    }
}

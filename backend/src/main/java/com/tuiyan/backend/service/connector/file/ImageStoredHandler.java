package com.tuiyan.backend.service.connector.file;

import com.tuiyan.backend.support.FileSniffer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 图片文件落桶处理：仅归档存储、不在此抽文本（图片识别需调多模态模型，由上层
 * {@link com.tuiyan.backend.service.ExperienceFileService} 走视觉识别得到正文），元信息标记 {@code image=true}。
 * <p>{@code @Order(35)} 排在文本类之后、音频(40)之前。
 */
@Component
@Order(35)
public class ImageStoredHandler implements StoredFileHandler {

    private static final long LIMIT_BYTES = 8L * 1024 * 1024;

    /** 受支持的图片扩展名（小写，含点）。 */
    private static final Set<String> EXTS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp");

    @Override
    public boolean supports(String lowerFilename, byte[] head) {
        if (FileSniffer.isImageMagic(head)) return true;
        return EXTS.stream().anyMatch(lowerFilename::endsWith);
    }

    @Override
    public long maxBytes() { return LIMIT_BYTES; }

    @Override
    public String label() { return "图片"; }

    @Override
    public Result extract(byte[] bytes) {
        // 图片不在此抽文本，仅归档；正文由上层调视觉模型识别得到
        return Result.of("", Map.of("image", true));
    }
}

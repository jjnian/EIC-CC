package com.tuiyan.backend.service.extraction;

import com.tuiyan.backend.support.FileSniffer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 图片处理：直接 base64 编码挂到 attachments 列表；超过单张大小或总额度则记原因后跳过。 */
@Component
@Order(30)
public class ImageFileHandler implements SourceFileHandler {

    // 单张图片字节上限：8 MB；超过则直接拒绝
    private static final long IMAGE_BYTE_LIMIT = 8L * 1024 * 1024;

    @Override
    public boolean supports(FileProbe probe) {
        return FileSniffer.isImageMagic(probe.head());
    }

    @Override
    public void handle(UploadedFile f, String safeName, ExtractionContext ctx, Map<String, Object> meta) throws IOException {
        long size = f.size();
        String contentType = f.contentType();
        if (size > IMAGE_BYTE_LIMIT) {
            throw new IllegalArgumentException(
                    "图片 " + safeName + " 超过 " + (IMAGE_BYTE_LIMIT / (1024 * 1024)) + " MB 限制");
        }
        if (ctx.imageBudgetReached()) {
            meta.put("type", "skipped");
            meta.put("reason", "已达全局图片预算 " + ExtractionContext.TOTAL_IMAGE_BUDGET + " 张");
            return;
        }
        String b64 = Base64.getEncoder().encodeToString(f.bytes());
        // contentType 缺失或非 image/* 时回退为 png，避免 LLM 拿到不合法的 media type
        String mediaType = (contentType != null && contentType.toLowerCase().startsWith("image/"))
                ? contentType : "image/png";
        String dataUrl = "data:" + mediaType + ";base64," + b64;
        Map<String, Object> att = new LinkedHashMap<>();
        att.put("type", "image");
        att.put("dataUrl", dataUrl);
        ctx.addImage(att);
        meta.put("type", "image");
    }
}

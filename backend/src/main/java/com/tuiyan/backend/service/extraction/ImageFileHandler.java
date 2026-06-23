package com.tuiyan.backend.service.extraction;

import com.tuiyan.backend.support.FileSniffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片处理：
 * <ol>
 *   <li>视觉识别成文字(OCR + 关键信息)，既喂入抽取的文本通道(让纯文本抽取模型也能理解图片内容)，
 *       又持久化到 {@code data_source.extra_json.transcript}，使「图片数据」可被索引 / 抽取到经验库 / 参与血缘；</li>
 *   <li>同时把原图 base64 挂到 attachments，供多模态模型获得更丰富信息（图片预算用尽则跳过附件）。</li>
 * </ol>
 * 识别失败且附件也未挂上时，记原因标记 skipped。
 */
@Component
@Order(30)
public class ImageFileHandler implements SourceFileHandler {

    private static final Logger log = LoggerFactory.getLogger(ImageFileHandler.class);

    // 单张图片字节上限：8 MB；超过则直接拒绝
    private static final long IMAGE_BYTE_LIMIT = 8L * 1024 * 1024;
    // 持久化到 extra_json 的识别正文上限（与音频 transcript 一致）
    private static final int RECOGNIZED_TEXT_BUDGET = 100_000;

    private final ImageRecognitionService recognition;

    public ImageFileHandler(ImageRecognitionService recognition) {
        this.recognition = recognition;
    }

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
        meta.put("type", "image");

        // 1) 视觉识别为文字：喂文本通道 + 持久化 transcript（图片数据据此可索引/抽经验/当血缘）
        boolean gotText = false;
        try {
            ctx.step().emit("recognizing_image", "正在识别图片 " + safeName + "…");
            String text = recognition.recognize(f.bytes(), safeName, contentType);
            if (text != null && !text.isBlank()) {
                ctx.appendSection("# 图片识别 " + safeName, text);
                meta.put("chars", text.length());
                meta.put("transcript", capText(text));
                gotText = true;
            }
        } catch (Exception e) {
            log.warn("[Vision] 图片 {} 识别失败: {}", safeName, e.toString());
        }

        // 2) 同时挂原图供多模态模型使用；图片预算用尽则跳过附件
        if (!ctx.imageBudgetReached()) {
            String b64 = Base64.getEncoder().encodeToString(f.bytes());
            String mediaType = (contentType != null && contentType.toLowerCase().startsWith("image/"))
                    ? contentType : "image/png";
            Map<String, Object> att = new LinkedHashMap<>();
            att.put("type", "image");
            att.put("dataUrl", "data:" + mediaType + ";base64," + b64);
            ctx.addImage(att);
        } else if (!gotText) {
            // 既没识别出文字、又超了图片预算：这张图无可用信息
            meta.put("type", "skipped");
            meta.put("reason", "已达全局图片预算 " + ExtractionContext.TOTAL_IMAGE_BUDGET + " 张，且未识别出文字");
        }
    }

    private static String capText(String text) {
        if (text.length() <= RECOGNIZED_TEXT_BUDGET) return text;
        return text.substring(0, RECOGNIZED_TEXT_BUDGET) + "\n[…truncated…]";
    }
}


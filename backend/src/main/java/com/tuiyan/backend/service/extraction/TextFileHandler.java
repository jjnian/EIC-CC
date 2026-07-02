package com.tuiyan.backend.service.extraction;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 纯文本类兜底处理：TXT / MD / CSV / TSV / JSON / LOG / SQL 等直接按 UTF-8 读入抽取上下文。
 * <p>{@code @Order(90)} 兜底排最后：所有专用 handler（PDF/DOCX/Excel/图片/音频/视频）都不认领时，
 * 按扩展名 / text 类 content-type 接住，避免文本文件被标记 skipped。
 */
@Component
@Order(90)
public class TextFileHandler implements SourceFileHandler {

    private static final long FILE_BYTES_LIMIT = 20L * 1024 * 1024;
    private static final int TEXT_CHAR_BUDGET = 200_000;

    @Override
    public boolean supports(FileProbe probe) {
        String ct = probe.lowerContentType();
        if (ct.startsWith("text/") || ct.contains("json") || ct.contains("xml")
                || ct.contains("yaml") || ct.contains("csv")) return true;
        String n = probe.lowerName();
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".markdown")
                || n.endsWith(".csv") || n.endsWith(".tsv") || n.endsWith(".json")
                || n.endsWith(".log") || n.endsWith(".sql") || n.endsWith(".xml")
                || n.endsWith(".yaml") || n.endsWith(".yml");
    }

    @Override
    public void handle(UploadedFile f, String safeName, ExtractionContext ctx, Map<String, Object> meta) {
        if (f.size() > FILE_BYTES_LIMIT) {
            meta.put("type", "skipped");
            meta.put("reason", "文本文件 " + safeName + " 超过 " + (FILE_BYTES_LIMIT / (1024 * 1024)) + " MB 限制");
            return;
        }
        String text = new String(f.bytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) {
            meta.put("type", "skipped");
            meta.put("reason", "文本内容为空");
            return;
        }
        int rawChars = text.length();
        if (text.length() > TEXT_CHAR_BUDGET) {
            text = text.substring(0, TEXT_CHAR_BUDGET) + "\n[…truncated…]";
            meta.put("truncated", true);
        }
        meta.put("type", "text");
        meta.put("chars", rawChars);
        ctx.appendSection(safeName, "# 文件 " + safeName, text);
    }
}

package com.tuiyan.backend.service.extraction;

import com.tuiyan.backend.support.XlsxTextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Excel（XLSX/XLS）处理：逐 Sheet 渲染成表格文本进抽取上下文。
 * 业务台账（字段口径 / 映射对照 / 手工主数据）是建图的重要原料。
 * <p>{@code @Order(25)} 排在 DOCX(20) 之后：两者都是 ZIP 容器，各自按扩展名/内容类型区分。
 */
@Component
@Order(25)
public class XlsxFileHandler implements SourceFileHandler {

    private static final Logger log = LoggerFactory.getLogger(XlsxFileHandler.class);
    private static final long FILE_BYTES_LIMIT = 30L * 1024 * 1024;

    @Override
    public boolean supports(FileProbe probe) {
        String ct = probe.lowerContentType();
        if (ct.contains("spreadsheetml") || ct.contains("ms-excel")) return true;
        String n = probe.lowerName();
        return n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".xlsm");
    }

    @Override
    public void handle(UploadedFile f, String safeName, ExtractionContext ctx, Map<String, Object> meta) {
        if (f.size() > FILE_BYTES_LIMIT) {
            meta.put("type", "skipped");
            meta.put("reason", "Excel " + safeName + " 超过 " + (FILE_BYTES_LIMIT / (1024 * 1024)) + " MB 限制");
            return;
        }
        try {
            XlsxTextExtractor.Result r = XlsxTextExtractor.extract(f.bytes());
            if (r.text() == null || r.text().isBlank()) {
                meta.put("type", "skipped");
                meta.put("reason", "Excel 无可读内容");
                return;
            }
            meta.put("type", "xlsx");
            meta.put("sheets", r.sheets());
            meta.put("rows", r.rows());
            meta.put("chars", r.text().length());
            if (r.truncated()) meta.put("truncated", true);
            ctx.appendSection(safeName, "# 表格 " + safeName, r.text());
        } catch (Exception e) {
            log.warn("[xlsx] {} 解析失败: {}", safeName, e.toString());
            meta.put("type", "skipped");
            meta.put("reason", "Excel 解析失败：" + e.getMessage());
        }
    }
}

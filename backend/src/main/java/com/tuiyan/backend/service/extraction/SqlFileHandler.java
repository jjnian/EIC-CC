package com.tuiyan.backend.service.extraction;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.support.SqlLineageExtractor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * SQL 脚本处理：先做确定性血缘解析（INSERT…SELECT / CTAS / VIEW / MERGE / UPDATE…FROM 的
 * 表级数据流，置信度 1.0、不经 LLM），图片段落到 {@link ExtractionContext#addGraphFragment}
 * 由编排层直接合并；SQL 原文仍作为正文送 LLM 做语义补充（业务命名、字段口径、注释里的规则）。
 * <p>{@code @Order(85)} 抢在文本兜底 (90) 之前认领 .sql / .ddl，两条路互补而非二选一。
 */
@Component
@Order(85)
public class SqlFileHandler implements SourceFileHandler {

    private static final long FILE_BYTES_LIMIT = 20L * 1024 * 1024;
    private static final int TEXT_CHAR_BUDGET = 200_000;

    @Override
    public boolean supports(FileProbe probe) {
        String n = probe.lowerName();
        return n.endsWith(".sql") || n.endsWith(".ddl") || probe.lowerContentType().contains("sql");
    }

    @Override
    public void handle(UploadedFile f, String safeName, ExtractionContext ctx, Map<String, Object> meta) {
        if (f.size() > FILE_BYTES_LIMIT) {
            meta.put("type", "skipped");
            meta.put("reason", "SQL 文件 " + safeName + " 超过 " + (FILE_BYTES_LIMIT / (1024 * 1024)) + " MB 限制");
            return;
        }
        String text = new String(f.bytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) {
            meta.put("type", "skipped");
            meta.put("reason", "SQL 内容为空");
            return;
        }

        SqlLineageExtractor.Result parsed = SqlLineageExtractor.parse(text);
        ObjectNode fragment = SqlLineageExtractor.toFragment(parsed.flows());
        if (fragment != null) {
            ctx.addGraphFragment(safeName, fragment);
            ctx.step().emit("sql_lineage", safeName + " 解析出 " + parsed.flows().size()
                    + " 条确定性数据流（" + parsed.statements() + " 条语句）");
        }
        meta.put("type", "sql");
        meta.put("chars", text.length());
        meta.put("statements", parsed.statements());
        meta.put("lineageFlows", parsed.flows().size());

        if (text.length() > TEXT_CHAR_BUDGET) {
            text = text.substring(0, TEXT_CHAR_BUDGET) + "\n[…truncated…]";
            meta.put("truncated", true);
        }
        ctx.appendSection(safeName, "# SQL 脚本 " + safeName, text);
    }
}

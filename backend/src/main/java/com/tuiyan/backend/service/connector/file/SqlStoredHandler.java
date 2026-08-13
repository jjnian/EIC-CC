package com.tuiyan.backend.service.connector.file;

import com.tuiyan.backend.service.agent.ExplorationAgentService;
import com.tuiyan.backend.service.connector.FileStoredService;
import com.tuiyan.backend.support.SqlLineageExtractor;
import com.tuiyan.backend.support.TextDecoder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SQL 脚本落桶处理：原文即经验正文，同时做确定性血缘解析，把表级数据流以
 * {@code <!-- EXPLORE_GRAPH {json} EXPLORE_GRAPH -->} 隐藏注释块嵌进正文末尾——
 * 经验库建图时 {@code ExperienceOntologyService} 会剥出该片段走结构化直连合并
 * （置信度 1.0、不经 LLM 重抽），SQL 正文本身仍参与 LLM 语义抽取互补。
 * <p>{@code @Order(28)} 排在 Excel(25) 之后、文本(30) 之前。
 */
@Component
@Order(28)
public class SqlStoredHandler implements StoredFileHandler {

    private static final long LIMIT_BYTES = 20L * 1024 * 1024;

    @Override
    public boolean supports(String lowerFilename, byte[] head) {
        return lowerFilename.endsWith(".sql") || lowerFilename.endsWith(".ddl");
    }

    @Override
    public long maxBytes() { return LIMIT_BYTES; }

    @Override
    public String label() { return "SQL"; }

    @Override
    public Result extract(byte[] bytes) {
        String text = TextDecoder.lenient(bytes);
        if (text == null || text.isBlank()) return Result.of("");
        SqlLineageExtractor.Result parsed = SqlLineageExtractor.parse(text);
        String graphJson = SqlLineageExtractor.toGraphJson(parsed.flows());
        String content;
        if (graphJson == null) {
            content = text;
        } else {
            // 上层 FileStoredService 按 200k 字符截尾且片段块嵌在正文末尾，
            // 须按片段实际大小预先截 SQL 正文，保证注释块完整存活（否则大脚本恰恰丢确定性血缘）
            String block = "\n\n<!-- " + ExplorationAgentService.GRAPH_MARKER + "\n"
                    + graphJson + "\n" + ExplorationAgentService.GRAPH_MARKER + " -->\n";
            int budget = Math.max(0,
                    FileStoredService.TEXT_CHAR_BUDGET - block.length() - 100);
            String body = text.length() > budget
                    ? text.substring(0, budget) + "\n[…truncated…]"
                    : text;
            content = body + block;
        }
        return Result.of(content, Map.of(
                "statements", parsed.statements(),
                "lineageFlows", parsed.flows().size()));
    }
}

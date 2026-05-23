package com.tuiyan.backend.support;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * 把 .docx 文件抽成纯文本：遍历段落 + 表格，跳过空段。
 * <p>表格按 {@code "| 单元格 | 单元格 |"} 的 markdown-ish 形式输出，保留二维结构，
 * 让 LLM 能从拼接文本里识别出表格语义。
 * <p>仅供 {@link com.tuiyan.backend.service.DocumentExtractionService} 内部使用。
 */
public final class DocxTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocxTextExtractor.class);

    private DocxTextExtractor() {}

    /** 抽取结果三元组：纯文本 + 段落数 + 表格数（后两个用于 metadata 展示）。 */
    public static class Result {
        public final String text;
        public final int paragraphs;
        public final int tables;
        public Result(String text, int paragraphs, int tables) {
            this.text = text;
            this.paragraphs = paragraphs;
            this.tables = tables;
        }
    }

    /**
     * 从输入流中抽取 docx 内容。
     * <p>段落与表格按文档顺序穿插写入也可以，但目前实现先输出全部段落再输出全部表格，
     * 实际效果对 LLM 阅读影响不大。
     */
    public static Result extract(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int paraCount = 0;
        int tableCount = 0;
        try (XWPFDocument doc = new XWPFDocument(in)) {
            // 段落
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t == null || t.isBlank()) continue;
                sb.append(t.strip()).append('\n');
                paraCount++;
            }
            // 表格
            for (XWPFTable table : doc.getTables()) {
                tableCount++;
                sb.append('\n');
                for (XWPFTableRow row : table.getRows()) {
                    sb.append('|');
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String cellText = cell.getText();
                        // 单元格内的换行会破坏 "|...|" 的行结构，统一替换为空格
                        sb.append(' ')
                          .append(cellText == null ? "" : cellText.strip().replace('\n', ' '))
                          .append(" |");
                    }
                    sb.append('\n');
                }
                sb.append('\n');
            }
        }
        return new Result(sb.toString(), paraCount, tableCount);
    }
}

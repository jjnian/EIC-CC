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
 * 把 .docx 文件抽成纯文本:遍历段落 + 表格,跳过空段。
 * 表格按 "| 单元格 | 单元格 |" 的 markdown-ish 形式输出,保留二维结构。
 */
public final class DocxTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocxTextExtractor.class);

    private DocxTextExtractor() {}

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

package com.tuiyan.backend.support;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Excel（XLSX/XLS）→ 文本：逐 Sheet 渲染成 markdown 表格风格的纯文本，供 LLM 抽取 / 经验正文。
 * 业务台账（字段口径、映射对照、手工主数据）大量存在于 Excel 中，是建图的重要原料。
 * <ul>
 *   <li>单 Sheet 行数、单行列数、总字符数均有上限，超限截断并标注；</li>
 *   <li>用 {@link DataFormatter} 按单元格显示格式取值（日期/数字不失真为浮点原值）；</li>
 *   <li>公式取缓存计算结果的显示值，取不到时回退公式文本。</li>
 * </ul>
 */
public final class XlsxTextExtractor {

    /** 单 Sheet 最多读取的数据行。 */
    private static final int MAX_ROWS_PER_SHEET = 300;
    /** 单行最多读取的列。 */
    private static final int MAX_COLS = 40;
    /** 总输出字符预算。 */
    private static final int TOTAL_CHAR_BUDGET = 80_000;

    private XlsxTextExtractor() {}

    public record Result(String text, int sheets, int rows, boolean truncated) {}

    public static Result extract(byte[] bytes) throws IOException {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter fmt = new DataFormatter();
            StringBuilder sb = new StringBuilder();
            boolean truncated = false;
            int totalRows = 0;
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sheet = wb.getSheetAt(si);
                sb.append("## Sheet: ").append(sheet.getSheetName()).append("\n\n");
                int emitted = 0;
                for (Row row : sheet) {
                    if (emitted >= MAX_ROWS_PER_SHEET) {
                        sb.append("…（该 Sheet 行数超 ").append(MAX_ROWS_PER_SHEET).append("，已截断）\n");
                        truncated = true;
                        break;
                    }
                    StringBuilder line = new StringBuilder("| ");
                    boolean hasValue = false;
                    short last = row.getLastCellNum();
                    int cols = Math.min(last < 0 ? 0 : last, MAX_COLS);
                    for (int ci = 0; ci < cols; ci++) {
                        Cell cell = row.getCell(ci);
                        String v = cell == null ? "" : safeFormat(fmt, cell);
                        if (!v.isBlank()) hasValue = true;
                        line.append(v.replace('\n', ' ').replace("|", "/")).append(" | ");
                    }
                    if (!hasValue) continue; // 跳过空行
                    sb.append(line).append('\n');
                    emitted++;
                    totalRows++;
                    if (sb.length() > TOTAL_CHAR_BUDGET) {
                        sb.append("\n…（内容超 ").append(TOTAL_CHAR_BUDGET).append(" 字符，已截断）\n");
                        return new Result(sb.toString(), si + 1, totalRows, true);
                    }
                }
                sb.append('\n');
            }
            return new Result(sb.toString(), wb.getNumberOfSheets(), totalRows, truncated);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Excel 解析失败: " + e.getMessage(), e);
        }
    }

    /** 公式单元格取缓存显示值失败时回退公式文本，不让单个坏单元格炸掉整表。 */
    private static String safeFormat(DataFormatter fmt, Cell cell) {
        try {
            return fmt.formatCellValue(cell);
        } catch (Exception e) {
            try { return cell.getCellFormula(); } catch (Exception ignore) { return ""; }
        }
    }
}

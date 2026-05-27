package com.tuiyan.backend.model.dto;

import java.util.List;

/** 表预览结果：列名 + 行数据 + 行数 + 是否截断。 */
public class TablePreviewResponse {
    private List<String> columns;
    private List<List<Object>> rows;
    private int rowCount;
    private boolean truncated;

    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }
    public List<List<Object>> getRows() { return rows; }
    public void setRows(List<List<Object>> rows) { this.rows = rows; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
}

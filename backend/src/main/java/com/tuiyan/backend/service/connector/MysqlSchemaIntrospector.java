package com.tuiyan.backend.service.connector;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL schema 内省：用 information_schema 一次性拉完表/列/键/FK。
 * <p>从 {@link JdbcConnectorService} 拆出的方言专用内省逻辑，行为完全等价。
 * <p>元信息 record（ColumnInfo / TableInfo 等）仍原地保留在 {@link JdbcConnectorService}，此处以全限定名引用。
 */
@Component
public class MysqlSchemaIntrospector {

    /** MySQL schema 内省：用 information_schema 一次性拉完表/列/键/FK。 */
    public JdbcConnectorService.DatabaseSchemaInfo introspect(Connection conn, String dbName, int tableLimit) throws SQLException {
        // 1) 表 + 视图 + 注释 + 行数估算（视图一并纳入：其定义体是血缘 ground truth）
        Map<String, TableBuilder> tables = new LinkedHashMap<>();
        String tablesSql = """
                SELECT TABLE_NAME, IFNULL(TABLE_COMMENT,''), IFNULL(TABLE_ROWS,0), TABLE_TYPE
                FROM information_schema.tables
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE IN ('BASE TABLE','VIEW')
                ORDER BY (TABLE_TYPE='VIEW'), TABLE_NAME
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(tablesSql)) {
            ps.setInt(1, tableLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    TableBuilder tb = new TableBuilder(name, rs.getString(2), rs.getLong(3));
                    if ("VIEW".equalsIgnoreCase(rs.getString(4))) tb.kind = "view";
                    tables.put(name, tb);
                }
            }
        }
        if (tables.isEmpty()) {
            return new JdbcConnectorService.DatabaseSchemaInfo("mysql", dbName, List.of());
        }
        // 后续查询都按首查拿到的表清单收敛,表很多(超出 limit)的库不再全库扫列/键
        List<String> names = new ArrayList<>(tables.keySet());
        String in = "(" + String.join(",", java.util.Collections.nCopies(names.size(), "?")) + ")";

        // 2) 列：name, type, nullable, default, comment, ordinal
        String colsSql = """
                SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
                       IFNULL(COLUMN_DEFAULT,''), IFNULL(COLUMN_COMMENT,''),
                       ORDINAL_POSITION, COLUMN_KEY
                FROM information_schema.columns
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN %s
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """.formatted(in);
        try (PreparedStatement ps = conn.prepareStatement(colsSql)) {
            bindAll(ps, names);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString(1);
                    TableBuilder tb = tables.get(t);
                    if (tb == null) continue;
                    JdbcConnectorService.ColumnInfo ci = new JdbcConnectorService.ColumnInfo(
                            rs.getString(2),
                            rs.getString(3),
                            "YES".equalsIgnoreCase(rs.getString(4)),
                            rs.getString(5),
                            rs.getString(6),
                            "PRI".equalsIgnoreCase(rs.getString(8)),
                            rs.getInt(7));
                    tb.columns.add(ci);
                }
            }
        }

        // 2.5) 视图定义体（血缘 ground truth：FROM/JOIN=派生关系，SELECT 聚合=指标口径）
        String viewSql = """
                SELECT TABLE_NAME, VIEW_DEFINITION
                FROM information_schema.views
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN %s
                """.formatted(in);
        try (PreparedStatement ps = conn.prepareStatement(viewSql)) {
            bindAll(ps, names);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableBuilder tb = tables.get(rs.getString(1));
                    if (tb == null) continue;
                    tb.kind = "view";
                    tb.definition = rs.getString(2);
                }
            }
        }

        // 3) 外键：information_schema.key_column_usage 里 REFERENCED_TABLE_NAME 非空的行
        String fkSql = """
                SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME,
                       REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
                FROM information_schema.key_column_usage
                WHERE TABLE_SCHEMA = DATABASE()
                  AND REFERENCED_TABLE_NAME IS NOT NULL
                  AND TABLE_NAME IN %s
                ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION
                """.formatted(in);
        try (PreparedStatement ps = conn.prepareStatement(fkSql)) {
            bindAll(ps, names);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString(1);
                    TableBuilder tb = tables.get(t);
                    if (tb == null) continue;
                    tb.foreignKeys.add(new JdbcConnectorService.ForeignKeyInfo(
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getString(5)));
                }
            }
        }

        // 4) 唯一约束（去掉 PRIMARY，因为已经在 columns.primaryKey 标了）
        String uniqSql = """
                SELECT s.TABLE_NAME, s.INDEX_NAME, s.COLUMN_NAME, s.SEQ_IN_INDEX
                FROM information_schema.statistics s
                WHERE s.TABLE_SCHEMA = DATABASE()
                  AND s.NON_UNIQUE = 0
                  AND s.INDEX_NAME <> 'PRIMARY'
                  AND s.TABLE_NAME IN %s
                ORDER BY s.TABLE_NAME, s.INDEX_NAME, s.SEQ_IN_INDEX
                """.formatted(in);
        try (PreparedStatement ps = conn.prepareStatement(uniqSql)) {
            bindAll(ps, names);
            try (ResultSet rs = ps.executeQuery()) {
                // index_name -> 累积 columns
                Map<String, List<String>> tmp = new LinkedHashMap<>();
                Map<String, String> idxToTable = new HashMap<>();
                while (rs.next()) {
                    String t = rs.getString(1);
                    if (!tables.containsKey(t)) continue;
                    String idx = rs.getString(2);
                    String key = t + "::" + idx;
                    tmp.computeIfAbsent(key, k -> new ArrayList<>()).add(rs.getString(3));
                    idxToTable.put(key, t);
                }
                for (Map.Entry<String, List<String>> e : tmp.entrySet()) {
                    String table = idxToTable.get(e.getKey());
                    String idxName = e.getKey().substring(table.length() + 2);
                    tables.get(table).uniqueKeys.add(new JdbcConnectorService.UniqueKeyInfo(idxName, e.getValue()));
                }
            }
        }

        return new JdbcConnectorService.DatabaseSchemaInfo("mysql", dbName,
                tables.values().stream().map(TableBuilder::build).toList());
    }

    /** 按顺序把表名绑进 IN 子句的占位符。 */
    private static void bindAll(PreparedStatement ps, List<String> names) throws SQLException {
        for (int i = 0; i < names.size(); i++) ps.setString(i + 1, names.get(i));
    }
}

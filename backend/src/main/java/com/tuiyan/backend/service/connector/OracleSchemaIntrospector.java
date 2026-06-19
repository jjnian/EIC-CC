package com.tuiyan.backend.service.connector;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Oracle schema 内省：用数据字典视图（USER_*）拉当前登录用户（schema）下的表/视图/列/键。
 * <p>从 {@link JdbcConnectorService} 委派的方言专用内省逻辑，结构与
 * {@link MysqlSchemaIntrospector} / {@link PgsqlSchemaIntrospector} 平行。
 * <p>要点：
 * <ul>
 *   <li>只看当前 schema（USER_TABLES / USER_VIEWS），不跨用户，避免误扫系统对象。</li>
 *   <li>列类型由 DATA_TYPE 拼上长度/精度还原成 {@code VARCHAR2(50)} / {@code NUMBER(10,2)} 形式。</li>
 *   <li>规避 LONG 字段坑：USER_TAB_COLUMNS.DATA_DEFAULT 与 USER_VIEWS.TEXT 均为 LONG，
 *       与其它列同查会报流错误，故默认值统一留空、视图定义体暂不取（仍登记为视图对象）。</li>
 *   <li>主键/外键/唯一键来自 USER_CONSTRAINTS（'P' / 'R' / 'U'）+ USER_CONS_COLUMNS。</li>
 * </ul>
 */
@Component
public class OracleSchemaIntrospector {

    /** Oracle schema 内省：USER_* 数据字典视图。 */
    public JdbcConnectorService.DatabaseSchemaInfo introspect(Connection conn, String dbName, int tableLimit) throws SQLException {
        Map<String, TableBuilder> tables = new LinkedHashMap<>();

        // 1) 基表（NUM_ROWS 估算行数）+ 表注释；视图排在后面，共享 tableLimit
        String tablesSql = """
                SELECT t.table_name, NVL(t.num_rows, 0) AS num_rows, NVL(tc.comments, '') AS comments
                FROM user_tables t
                LEFT JOIN user_tab_comments tc ON tc.table_name = t.table_name AND tc.table_type = 'TABLE'
                ORDER BY t.table_name
                """;
        try (PreparedStatement ps = conn.prepareStatement(tablesSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next() && tables.size() < tableLimit) {
                String name = rs.getString(1);
                tables.put(name, new TableBuilder(name, rs.getString(3), rs.getLong(2)));
            }
        }

        // 2) 视图（定义体为 LONG，避免流错误暂不取，仅登记为视图对象）+ 视图注释
        String viewsSql = """
                SELECT v.view_name, NVL(tc.comments, '') AS comments
                FROM user_views v
                LEFT JOIN user_tab_comments tc ON tc.table_name = v.view_name AND tc.table_type = 'VIEW'
                ORDER BY v.view_name
                """;
        try (PreparedStatement ps = conn.prepareStatement(viewsSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next() && tables.size() < tableLimit) {
                String name = rs.getString(1);
                if (tables.containsKey(name)) continue;
                TableBuilder tb = new TableBuilder(name, rs.getString(2), 0L);
                tb.kind = "view";
                tables.put(name, tb);
            }
        }

        if (tables.isEmpty()) {
            return new JdbcConnectorService.DatabaseSchemaInfo("oracle", dbName, List.of());
        }
        // 后续查询都按首查拿到的对象清单收敛
        List<String> names = new ArrayList<>(tables.keySet());
        String in = "(" + String.join(",", java.util.Collections.nCopies(names.size(), "?")) + ")";

        // 3) 主键列（先收集，供构造 ColumnInfo 时标 primaryKey）
        Map<String, Set<String>> pkCols = new HashMap<>();
        String pkSql = """
                SELECT cc.table_name, cc.column_name
                FROM user_constraints c
                JOIN user_cons_columns cc ON cc.constraint_name = c.constraint_name
                WHERE c.constraint_type = 'P' AND cc.table_name IN %s
                """.formatted(in);
        try (PreparedStatement ps = conn.prepareStatement(pkSql)) {
            bindAll(ps, names);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pkCols.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
                }
            }
        }

        // 4) 列：类型由 DATA_TYPE + 长度/精度还原；默认值因 LONG 暂留空
        String colsSql = """
                SELECT col.table_name,
                       col.column_name,
                       col.data_type
                         || CASE
                              WHEN col.data_type IN ('VARCHAR2','CHAR','NVARCHAR2','NCHAR','RAW')
                                   THEN '(' || col.char_length || ')'
                              WHEN col.data_type = 'NUMBER' AND col.data_precision IS NOT NULL
                                   THEN '(' || col.data_precision
                                        || CASE WHEN NVL(col.data_scale, 0) > 0 THEN ',' || col.data_scale ELSE '' END
                                        || ')'
                              ELSE ''
                            END AS data_type,
                       col.nullable,
                       NVL(cc.comments, '') AS comments,
                       col.column_id
                FROM user_tab_columns col
                LEFT JOIN user_col_comments cc
                       ON cc.table_name = col.table_name AND cc.column_name = col.column_name
                WHERE col.table_name IN %s
                ORDER BY col.table_name, col.column_id
                """.formatted(in);
        try (PreparedStatement ps = conn.prepareStatement(colsSql)) {
            bindAll(ps, names);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString(1);
                    TableBuilder tb = tables.get(t);
                    if (tb == null) continue;
                    String colName = rs.getString(2);
                    boolean isPk = pkCols.getOrDefault(t, Set.of()).contains(colName);
                    tb.columns.add(new JdbcConnectorService.ColumnInfo(
                            colName,
                            rs.getString(3),
                            "Y".equalsIgnoreCase(rs.getString(4)),
                            "",                       // DATA_DEFAULT 为 LONG，避免流错误不取
                            rs.getString(5),
                            isPk,
                            rs.getInt(6)));
                }
            }
        }

        // 5) 外键：'R' 约束 → 关联被引用约束(主键/唯一)的列，按 position 对齐
        String fkSql = """
                SELECT c.table_name, c.constraint_name, cc.column_name,
                       rc.table_name AS ref_table, rcc.column_name AS ref_column
                FROM user_constraints c
                JOIN user_cons_columns cc ON cc.constraint_name = c.constraint_name
                JOIN user_constraints rc ON rc.constraint_name = c.r_constraint_name
                JOIN user_cons_columns rcc ON rcc.constraint_name = rc.constraint_name AND rcc.position = cc.position
                WHERE c.constraint_type = 'R' AND c.table_name IN %s
                ORDER BY c.table_name, c.constraint_name, cc.position
                """.formatted(in);
        try (PreparedStatement ps = conn.prepareStatement(fkSql)) {
            bindAll(ps, names);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableBuilder tb = tables.get(rs.getString(1));
                    if (tb == null) continue;
                    tb.foreignKeys.add(new JdbcConnectorService.ForeignKeyInfo(
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getString(5)));
                }
            }
        }

        // 6) 唯一约束（constraint_type='U'；主键已在 columns 上标过）
        String uniqSql = """
                SELECT cc.table_name, c.constraint_name, cc.column_name
                FROM user_constraints c
                JOIN user_cons_columns cc ON cc.constraint_name = c.constraint_name
                WHERE c.constraint_type = 'U' AND cc.table_name IN %s
                ORDER BY cc.table_name, c.constraint_name, cc.position
                """.formatted(in);
        try (PreparedStatement ps = conn.prepareStatement(uniqSql)) {
            bindAll(ps, names);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, List<String>> tmp = new LinkedHashMap<>();
                Map<String, String> idxToTable = new HashMap<>();
                while (rs.next()) {
                    String t = rs.getString(1);
                    if (!tables.containsKey(t)) continue;
                    String key = t + "::" + rs.getString(2);
                    tmp.computeIfAbsent(key, k -> new ArrayList<>()).add(rs.getString(3));
                    idxToTable.put(key, t);
                }
                for (Map.Entry<String, List<String>> e : tmp.entrySet()) {
                    String table = idxToTable.get(e.getKey());
                    String name = e.getKey().substring(table.length() + 2);
                    tables.get(table).uniqueKeys.add(new JdbcConnectorService.UniqueKeyInfo(name, e.getValue()));
                }
            }
        }

        return new JdbcConnectorService.DatabaseSchemaInfo("oracle", dbName,
                tables.values().stream().map(TableBuilder::build).toList());
    }

    /** 按顺序把对象名绑进 IN 子句的占位符。 */
    private static void bindAll(PreparedStatement ps, List<String> names) throws SQLException {
        for (int i = 0; i < names.size(); i++) ps.setString(i + 1, names.get(i));
    }
}

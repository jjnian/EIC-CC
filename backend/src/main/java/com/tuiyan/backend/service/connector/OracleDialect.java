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
 * Oracle 方言：USER_* 数据字典内省、双引号、{@code FETCH FIRST n ROWS ONLY}。
 * <p>达梦(DM)高度兼容 Oracle 数据字典，由 {@link DmDialect} 继承本类、仅覆写 kind 与连接信息。
 */
@Component
public class OracleDialect extends AbstractSqlDialect {

    @Override
    public String kind() {
        return SourceKind.ORACLE;
    }

    @Override
    public int defaultPort() {
        return 1521;
    }

    @Override
    public String jdbcUrl(String host, int port, String database, String params) {
        // thin 驱动 service-name 形式：database 字段填服务名（或 SID）。thin URL 不接受 ?k=v 查询参数，故忽略 params。
        return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
    }

    @Override
    public String driverClass() {
        return "oracle.jdbc.OracleDriver";
    }

    @Override
    public String quote(String name) {
        int dot = name.indexOf('.');
        if (dot > 0) {
            String sch = name.substring(0, dot), tbl = name.substring(dot + 1);
            return "\"" + sch.replace("\"", "\"\"") + "\".\"" + tbl.replace("\"", "\"\"") + "\"";
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String rowLimitClause(int n) {
        return " FETCH FIRST " + n + " ROWS ONLY";
    }

    @Override
    public boolean hasRowLimit(String upperSql) {
        return upperSql.contains("FETCH FIRST") || upperSql.contains("FETCH NEXT") || upperSql.contains("ROWNUM");
    }

    @Override
    public String listTablesSql() {
        // 当前登录用户(schema)下的表（Oracle / 达梦 数据字典）
        return "SELECT table_name FROM user_tables ORDER BY table_name";
    }

    @Override
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

        // 2) 视图 + 视图注释
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

        // 2.5) 视图定义体（血缘 ground truth）。TEXT 为 LONG 列：放在 SELECT 末位、按列序即读、
        //      fetchSize=1，规避「流已关闭」问题；仍失败则整体降级为无定义体（与旧行为一致）。
        String viewDefSql = "SELECT view_name, text FROM user_views";
        try (PreparedStatement ps = conn.prepareStatement(viewDefSql)) {
            ps.setFetchSize(1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String def = rs.getString(2);
                    TableBuilder tb = tables.get(name);
                    if (tb != null && "view".equals(tb.kind) && def != null && !def.isBlank()) {
                        tb.definition = def;
                    }
                }
            }
        } catch (SQLException e) {
            // 部分驱动/版本对 LONG 流读取受限：视图仍登记，只是没有定义体
        }

        if (tables.isEmpty()) {
            return build(dbName, tables);
        }
        // 后续查询都按首查拿到的对象清单收敛
        List<String> names = new ArrayList<>(tables.keySet());
        String in = inPlaceholders(names.size());

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
                                   THEN '(' || col.data_length || ')'
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
                UniqueKeyAccumulator acc = new UniqueKeyAccumulator();
                while (rs.next()) {
                    String t = rs.getString(1);
                    if (!tables.containsKey(t)) continue;
                    acc.add(t, rs.getString(2), rs.getString(3));
                }
                acc.flushTo(tables);
            }
        }

        // 7) 存储过程/函数/包体源码：user_source 按行存储，聚合成整段定义体（ETL 血缘 ground truth）。
        //    无权限时整体降级；行数极多的对象按 MAX_ROUTINE_DEF_CHARS 截断。
        List<JdbcConnectorService.RoutineInfo> routines = new ArrayList<>();
        String srcSql = """
                SELECT name, type, text
                FROM user_source
                WHERE type IN ('PROCEDURE', 'FUNCTION', 'PACKAGE BODY')
                ORDER BY name, type, line
                """;
        Map<String, StringBuilder> srcByRoutine = new LinkedHashMap<>();   // name type → 源码聚合
        try (PreparedStatement ps = conn.prepareStatement(srcSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String key = rs.getString(1) + " " + rs.getString(2);
                StringBuilder b = srcByRoutine.get(key);
                if (b == null) {
                    if (srcByRoutine.size() >= MAX_ROUTINES) continue;
                    b = new StringBuilder();
                    srcByRoutine.put(key, b);
                }
                String line = rs.getString(3);
                if (line == null || b.length() >= MAX_ROUTINE_DEF_CHARS) continue;
                b.append(line);
                if (!line.endsWith("\n")) b.append('\n');   // 行注释依赖换行终止，保证解析安全
            }
        } catch (SQLException e) {
            // 无权限：跳过存储过程，表结构照常返回
        }
        for (Map.Entry<String, StringBuilder> en : srcByRoutine.entrySet()) {
            int sep = en.getKey().indexOf(' ');
            routines.add(routineOf(en.getKey().substring(0, sep),
                    en.getKey().substring(sep + 1), "", en.getValue().toString()));
        }

        return build(dbName, tables, routines);
    }
}

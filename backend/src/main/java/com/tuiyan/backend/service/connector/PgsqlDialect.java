package com.tuiyan.backend.service.connector;

import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL 方言：pg_catalog + information_schema 内省、双引号（按 schema.table 拆分）、LIMIT。
 * <p>内部一律以 {@code schema.table} 为 key，跨 schema 同名表不互相覆盖；展示名在 public 下省略前缀。
 */
@Component
public class PgsqlDialect extends AbstractSqlDialect {

    @Override
    public String kind() {
        return SourceKind.PGSQL;
    }

    @Override
    public int defaultPort() {
        return 5432;
    }

    @Override
    public String jdbcUrl(String host, int port, String database, String params) {
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        if (params != null && !params.isBlank()) url += "?" + params;
        return url;
    }

    @Override
    public String driverClass() {
        return "org.postgresql.Driver";
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
        return " LIMIT " + n;
    }

    @Override
    public boolean hasRowLimit(String upperSql) {
        return upperSql.contains("LIMIT");
    }

    @Override
    public String listTablesSql() {
        return "SELECT tablename FROM pg_catalog.pg_tables " +
               "WHERE schemaname NOT IN ('pg_catalog','information_schema') " +
               "ORDER BY tablename";
    }

    @Override
    public JdbcConnectorService.DatabaseSchemaInfo introspect(Connection conn, String dbName, int tableLimit) throws SQLException {
        // key 一律 schema.table,防跨 schema 同名表互相覆盖
        Map<String, TableBuilder> tables = new LinkedHashMap<>();

        // 1) 表 + 视图 + 物化视图 + 分区父表 + 注释（视图的 pg_get_viewdef 是血缘 ground truth）
        String tablesSql = """
                SELECT n.nspname,
                       c.relname,
                       COALESCE(obj_description(c.oid, 'pg_class'), '') AS comment,
                       COALESCE(c.reltuples::bigint, 0) AS row_est,
                       c.relkind,
                       CASE WHEN c.relkind IN ('v','m')
                            THEN COALESCE(pg_get_viewdef(c.oid, true), '') ELSE '' END AS definition
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind IN ('r', 'v', 'm', 'p')
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                ORDER BY (c.relkind IN ('v','m')), n.nspname, c.relname
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(tablesSql)) {
            ps.setInt(1, tableLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String schema = rs.getString(1);
                    String name = rs.getString(2);
                    TableBuilder tb = new TableBuilder(displayName(schema, name), rs.getString(3), rs.getLong(4));
                    String relkind = rs.getString(5);
                    if ("v".equals(relkind) || "m".equals(relkind)) {
                        tb.kind = "view";
                        tb.definition = rs.getString(6);
                    }
                    tables.put(schema + "." + name, tb);
                }
            }
        }
        // 1.5) 分区子表折叠：声明式分区的子分区不作为独立实体（按月分区会灌入几十个同构“表”，
        //      每个都变成图上一个节点），统一折叠进分区父表(relkind='p')。PG10 之前无 relispartition，
        //      查询失败静默跳过（老版本也没有声明式分区，无需折叠）。
        String partSql = """
                SELECT n.nspname, c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relispartition
                """;
        try (PreparedStatement ps = conn.prepareStatement(partSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tables.remove(rs.getString(1) + "." + rs.getString(2));
            }
        } catch (SQLException e) {
            // PG10 之前无 relispartition：无声明式分区，跳过折叠
        }
        if (tables.isEmpty()) {
            return build(dbName, tables);
        }
        Array keyArray = conn.createArrayOf("text", tables.keySet().toArray());

        // 2) 列：information_schema.columns 为骨架,join pg_attribute 取完整类型、pg_description 取注释
        String colsSql = """
                SELECT c.table_schema,
                       c.table_name,
                       c.column_name,
                       COALESCE(format_type(a.atttypid, a.atttypmod), c.udt_name) AS data_type,
                       c.is_nullable,
                       COALESCE(c.column_default, '') AS default_val,
                       COALESCE(pgd.description, '') AS comment,
                       c.ordinal_position,
                       CASE WHEN pk.column_name IS NOT NULL THEN 'PRI' ELSE '' END AS col_key
                FROM information_schema.columns c
                LEFT JOIN pg_catalog.pg_namespace nsp ON nsp.nspname = c.table_schema
                LEFT JOIN pg_catalog.pg_class cls
                       ON cls.relnamespace = nsp.oid AND cls.relname = c.table_name
                LEFT JOIN pg_catalog.pg_attribute a
                       ON a.attrelid = cls.oid AND a.attname = c.column_name AND NOT a.attisdropped
                LEFT JOIN pg_catalog.pg_description pgd
                       ON pgd.objoid = cls.oid AND pgd.objsubid = c.ordinal_position
                LEFT JOIN (
                    SELECT kcu.table_schema, kcu.table_name, kcu.column_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON tc.constraint_name = kcu.constraint_name
                     AND tc.table_schema = kcu.table_schema
                    WHERE tc.constraint_type = 'PRIMARY KEY'
                ) pk ON pk.table_schema = c.table_schema
                    AND pk.table_name = c.table_name
                    AND pk.column_name = c.column_name
                WHERE c.table_schema || '.' || c.table_name = ANY(?)
                ORDER BY c.table_schema, c.table_name, c.ordinal_position
                """;
        try (PreparedStatement ps = conn.prepareStatement(colsSql)) {
            ps.setArray(1, keyArray);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableBuilder tb = tables.get(rs.getString(1) + "." + rs.getString(2));
                    if (tb == null) continue;
                    tb.columns.add(new JdbcConnectorService.ColumnInfo(
                            rs.getString(3),
                            rs.getString(4),
                            "YES".equalsIgnoreCase(rs.getString(5)),
                            rs.getString(6),
                            rs.getString(7),
                            "PRI".equals(rs.getString(9)),
                            rs.getInt(8)));
                }
            }
        }

        // 3) 外键(引用侧也带 schema,展示名同样在 public 下省略前缀)
        String fkSql = """
                SELECT tc.table_schema, tc.table_name, tc.constraint_name, kcu.column_name,
                       ccu.table_schema AS foreign_schema,
                       ccu.table_name AS foreign_table,
                       ccu.column_name AS foreign_column
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_name = tc.constraint_name
                 AND ccu.table_schema = tc.table_schema
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND tc.table_schema || '.' || tc.table_name = ANY(?)
                ORDER BY tc.table_schema, tc.table_name, tc.constraint_name, kcu.ordinal_position
                """;
        try (PreparedStatement ps = conn.prepareStatement(fkSql)) {
            ps.setArray(1, keyArray);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableBuilder tb = tables.get(rs.getString(1) + "." + rs.getString(2));
                    if (tb == null) continue;
                    tb.foreignKeys.add(new JdbcConnectorService.ForeignKeyInfo(
                            rs.getString(3),
                            rs.getString(4),
                            displayName(rs.getString(5), rs.getString(6)),
                            rs.getString(7)));
                }
            }
        }

        // 4) 唯一索引（排除主键）
        String uniqSql = """
                SELECT n.nspname AS table_schema,
                       t.relname AS table_name,
                       i.relname AS index_name,
                       a.attname AS column_name,
                       array_position(ix.indkey, a.attnum) AS seq
                FROM pg_class t
                JOIN pg_index ix ON t.oid = ix.indrelid
                JOIN pg_class i ON i.oid = ix.indexrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
                WHERE ix.indisunique = true AND ix.indisprimary = false
                  AND n.nspname || '.' || t.relname = ANY(?)
                ORDER BY n.nspname, t.relname, i.relname, seq
                """;
        try (PreparedStatement ps = conn.prepareStatement(uniqSql)) {
            ps.setArray(1, keyArray);
            try (ResultSet rs = ps.executeQuery()) {
                UniqueKeyAccumulator acc = new UniqueKeyAccumulator();
                while (rs.next()) {
                    String tableKey = rs.getString(1) + "." + rs.getString(2);
                    if (!tables.containsKey(tableKey)) continue;
                    acc.add(tableKey, rs.getString(3), rs.getString(4));
                }
                acc.flushTo(tables);
            }
        }

        // 5) 存储过程/函数定义体（仅 sql/plpgsql，pg_get_functiondef 还原完整源码；PG 11+ 有 prokind）。
        //    版本差异 / 权限不足整体失败时静默降级，不影响表内省。
        List<JdbcConnectorService.RoutineInfo> routines = new ArrayList<>();
        String routineSql = """
                SELECT n.nspname,
                       p.proname,
                       p.prokind,
                       COALESCE(d.description, '') AS comment,
                       pg_get_functiondef(p.oid) AS definition
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                JOIN pg_language l ON l.oid = p.prolang
                LEFT JOIN pg_description d ON d.objoid = p.oid AND d.objsubid = 0
                WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                  AND p.prokind IN ('f', 'p')
                  AND l.lanname IN ('sql', 'plpgsql')
                ORDER BY n.nspname, p.proname
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(routineSql)) {
            ps.setInt(1, MAX_ROUTINES);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = "p".equals(rs.getString(3)) ? "procedure" : "function";
                    routines.add(routineOf(displayName(rs.getString(1), rs.getString(2)), kind,
                            rs.getString(4), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            // 老版本无 prokind / 无权限：跳过存储过程，表结构照常返回
        }

        // 6) 触发器：挂载表 + 触发器函数源码。审计/同步/汇总表的写入血缘藏在这里——函数体常写
        //    INSERT INTO audit VALUES(NEW.*)（无 FROM），必须带挂载表才能推出「挂载表 → 写入目标」。
        List<JdbcConnectorService.TriggerInfo> triggers = new ArrayList<>();
        String trgSql = """
                SELECT n.nspname,
                       c.relname AS table_name,
                       t.tgname,
                       pg_get_triggerdef(t.oid, true) AS def,
                       COALESCE(p.prosrc, '') AS func_body
                FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_proc p ON p.oid = t.tgfoid
                WHERE NOT t.tgisinternal
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                ORDER BY n.nspname, c.relname, t.tgname
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(trgSql)) {
            ps.setInt(1, MAX_TRIGGERS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    triggers.add(triggerOf(rs.getString(3),
                            displayName(rs.getString(1), rs.getString(2)),
                            timingOf(rs.getString(4)), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            // 无权限：跳过触发器，表结构照常返回
        }

        // 7) 视图依赖目录：数据库自己维护的「视图 ← 基表」依赖，兜底正则解析不了的嵌套/复杂视图定义。
        List<JdbcConnectorService.DependencyInfo> deps = new ArrayList<>();
        String depSql = """
                SELECT DISTINCT view_schema, view_name, table_schema, table_name
                FROM information_schema.view_table_usage
                WHERE view_schema NOT IN ('pg_catalog', 'information_schema')
                """;
        try (PreparedStatement ps = conn.prepareStatement(depSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                deps.add(new JdbcConnectorService.DependencyInfo(
                        displayName(rs.getString(1), rs.getString(2)), "view",
                        displayName(rs.getString(3), rs.getString(4))));
            }
        } catch (SQLException e) {
            // 无权限：跳过依赖目录，视图血缘退回正则解析
        }

        return build(dbName, tables, routines, triggers, deps);
    }

    /** 从 pg_get_triggerdef 的完整定义里摘出时机/事件（如 "AFTER INSERT OR UPDATE"），摘不出返回空串。 */
    private static String timingOf(String triggerDef) {
        if (triggerDef == null) return "";
        Matcher m = Pattern.compile(
                "\\b(BEFORE|AFTER|INSTEAD OF)\\s+(INSERT|UPDATE|DELETE|TRUNCATE)((?:\\s+OR\\s+\\w+)*)",
                Pattern.CASE_INSENSITIVE).matcher(triggerDef);
        return m.find() ? m.group().replaceAll("\\s+", " ") : "";
    }

    /** 对外展示名:public 下省略 schema 前缀,其余 schema.table。 */
    private static String displayName(String schema, String table) {
        return (schema == null || schema.isBlank() || "public".equals(schema)) ? table : schema + "." + table;
    }
}

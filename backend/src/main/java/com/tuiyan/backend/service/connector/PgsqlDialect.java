package com.tuiyan.backend.service.connector;

import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

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

        return build(dbName, tables);
    }

    /** 对外展示名:public 下省略 schema 前缀,其余 schema.table。 */
    private static String displayName(String schema, String table) {
        return (schema == null || schema.isBlank() || "public".equals(schema)) ? table : schema + "." + table;
    }
}

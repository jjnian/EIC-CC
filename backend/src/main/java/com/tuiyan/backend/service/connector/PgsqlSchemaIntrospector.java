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
 * PgSQL schema 内省：用 pg_catalog + information_schema 拼装。
 * <p>从 {@link JdbcConnectorService} 拆出的方言专用内省逻辑，行为完全等价。
 * <p>元信息 record（ColumnInfo / TableInfo 等）仍原地保留在 {@link JdbcConnectorService}，此处以全限定名引用。
 */
@Component
public class PgsqlSchemaIntrospector {

    /** PgSQL schema 内省：用 pg_catalog + information_schema 拼装。 */
    public JdbcConnectorService.DatabaseSchemaInfo introspect(Connection conn, String dbName, int tableLimit) throws SQLException {
        Map<String, TableBuilder> tables = new LinkedHashMap<>();

        // 1) 表 + 注释（只取 public schema 下 BASE TABLE；用户用其它 schema 时可以扩展）
        String tablesSql = """
                SELECT c.relname,
                       COALESCE(obj_description(c.oid, 'pg_class'), '') AS comment,
                       COALESCE(c.reltuples::bigint, 0) AS row_est
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind = 'r'
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                ORDER BY n.nspname, c.relname
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(tablesSql)) {
            ps.setInt(1, tableLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    tables.put(name, new TableBuilder(name, rs.getString(2), rs.getLong(3)));
                }
            }
        }
        if (tables.isEmpty()) {
            return new JdbcConnectorService.DatabaseSchemaInfo("pgsql", dbName, List.of());
        }

        // 2) 列：从 information_schema.columns 拉，再 join pg_description 取 comment
        String colsSql = """
                SELECT c.table_name,
                       c.column_name,
                       c.udt_name AS data_type,
                       c.is_nullable,
                       COALESCE(c.column_default, '') AS default_val,
                       COALESCE(pgd.description, '') AS comment,
                       c.ordinal_position,
                       CASE WHEN pk.column_name IS NOT NULL THEN 'PRI' ELSE '' END AS col_key
                FROM information_schema.columns c
                LEFT JOIN pg_catalog.pg_statio_all_tables st
                       ON st.schemaname = c.table_schema AND st.relname = c.table_name
                LEFT JOIN pg_catalog.pg_description pgd
                       ON pgd.objoid = st.relid AND pgd.objsubid = c.ordinal_position
                LEFT JOIN (
                    SELECT kcu.table_name, kcu.column_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON tc.constraint_name = kcu.constraint_name
                     AND tc.table_schema = kcu.table_schema
                    WHERE tc.constraint_type = 'PRIMARY KEY'
                ) pk ON pk.table_name = c.table_name AND pk.column_name = c.column_name
                WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema')
                ORDER BY c.table_name, c.ordinal_position
                """;
        try (PreparedStatement ps = conn.prepareStatement(colsSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String t = rs.getString(1);
                TableBuilder tb = tables.get(t);
                if (tb == null) continue;
                tb.columns.add(new JdbcConnectorService.ColumnInfo(
                        rs.getString(2),
                        rs.getString(3),
                        "YES".equalsIgnoreCase(rs.getString(4)),
                        rs.getString(5),
                        rs.getString(6),
                        "PRI".equals(rs.getString(8)),
                        rs.getInt(7)));
            }
        }

        // 3) 外键
        String fkSql = """
                SELECT tc.table_name, tc.constraint_name, kcu.column_name,
                       ccu.table_name AS foreign_table, ccu.column_name AS foreign_column
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_name = tc.constraint_name
                 AND ccu.table_schema = tc.table_schema
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND tc.table_schema NOT IN ('pg_catalog', 'information_schema')
                ORDER BY tc.table_name, tc.constraint_name, kcu.ordinal_position
                """;
        try (PreparedStatement ps = conn.prepareStatement(fkSql);
             ResultSet rs = ps.executeQuery()) {
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

        // 4) 唯一索引（排除主键）
        String uniqSql = """
                SELECT t.relname AS table_name,
                       i.relname AS index_name,
                       a.attname AS column_name,
                       array_position(ix.indkey, a.attnum) AS seq
                FROM pg_class t
                JOIN pg_index ix ON t.oid = ix.indrelid
                JOIN pg_class i ON i.oid = ix.indexrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
                WHERE ix.indisunique = true AND ix.indisprimary = false
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                ORDER BY t.relname, i.relname, seq
                """;
        try (PreparedStatement ps = conn.prepareStatement(uniqSql);
             ResultSet rs = ps.executeQuery()) {
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

        return new JdbcConnectorService.DatabaseSchemaInfo("pgsql", dbName,
                tables.values().stream().map(TableBuilder::build).toList());
    }
}

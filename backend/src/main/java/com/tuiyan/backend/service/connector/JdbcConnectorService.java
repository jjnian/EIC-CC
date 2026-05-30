package com.tuiyan.backend.service.connector;

import com.tuiyan.backend.model.dto.DataSourceTestResponse;
import com.tuiyan.backend.model.dto.SqlExecuteResponse;
import com.tuiyan.backend.model.dto.TablePreviewResponse;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * MySQL / PostgreSQL 通用连接器：每次操作建临时 HikariDataSource，用完即关，不缓存连接池。
 * <p>SQL 仅允许只读语句（SELECT/SHOW/DESC/DESCRIBE/EXPLAIN），强制 LIMIT 兜底。
 */
@Service
public class JdbcConnectorService {

    private static final Logger log = LoggerFactory.getLogger(JdbcConnectorService.class);

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;
    public static final int CONNECT_TIMEOUT_MS = 5_000;
    public static final int QUERY_TIMEOUT_SEC = 15;

    // 只读语句白名单：开头关键字（忽略大小写 + 前导空白）
    private static final Pattern READONLY_PATTERN =
            Pattern.compile("^\\s*(SELECT|SHOW|DESC|DESCRIBE|EXPLAIN)\\b.*",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // ============================================================
    // Schema 内省专用 record：列、外键、表、整库 schema
    // ============================================================

    /** 单列元信息：name + 类型 + 是否可空 + 默认值 + 注释 + 是否主键 + 序号 */
    public record ColumnInfo(String name,
                             String dataType,
                             boolean nullable,
                             String defaultValue,
                             String comment,
                             boolean primaryKey,
                             int ordinalPosition) {}

    /** 外键关系：本表 col → 引用表的 col （在表内部，方便序列化时按表分组） */
    public record ForeignKeyInfo(String constraintName,
                                 String fromColumn,
                                 String toTable,
                                 String toColumn) {}

    /** 唯一约束（含 unique index）：方便给 LLM 提示业务键 */
    public record UniqueKeyInfo(String name, List<String> columns) {}

    /** 单表元信息：表名 + 注释 + 列 + 外键 + 唯一约束 + 估算行数 */
    public record TableInfo(String name,
                            String comment,
                            List<ColumnInfo> columns,
                            List<ForeignKeyInfo> foreignKeys,
                            List<UniqueKeyInfo> uniqueKeys,
                            Long estimatedRows) {}

    /** 整库 schema：kind + database + 全表 */
    public record DatabaseSchemaInfo(String kind,
                                     String database,
                                     List<TableInfo> tables) {}

    /** 按 kind + config 构造一个独立、最小化的 HikariDataSource，调用方负责关。 */
    private DataSource buildTempDataSource(String kind, Map<String, Object> cfg) {
        String host = strOf(cfg, "host", "localhost");
        Object portObj = cfg.get("port");
        int port = portObj instanceof Number n ? n.intValue() : ("mysql".equals(kind) ? 3306 : 5432);
        String db = strOf(cfg, "database", "");
        String user = strOf(cfg, "username", "");
        String pwd = strOf(cfg, "password", "");
        String params = strOf(cfg, "params", "");

        String url;
        String driver;
        if ("mysql".equals(kind)) {
            url = "jdbc:mysql://" + host + ":" + port + "/" + db;
            if (!params.isBlank()) url += "?" + params;
            driver = "com.mysql.cj.jdbc.Driver";
        } else if ("pgsql".equals(kind)) {
            url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
            if (!params.isBlank()) url += "?" + params;
            driver = "org.postgresql.Driver";
        } else {
            throw new IllegalArgumentException("不支持的 kind: " + kind);
        }

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(url);
        hc.setUsername(user);
        hc.setPassword(pwd);
        hc.setDriverClassName(driver);
        hc.setMaximumPoolSize(2);
        hc.setMinimumIdle(0);
        hc.setConnectionTimeout(CONNECT_TIMEOUT_MS);
        hc.setIdleTimeout(10_000);
        hc.setPoolName("ds-temp-" + System.nanoTime());
        return new HikariDataSource(hc);
    }

    private static String strOf(Map<String, Object> m, String k, String dft) {
        Object v = m.get(k);
        return v == null ? dft : String.valueOf(v);
    }

    /** 连接测试：建池 → getConnection → isValid(2s)；返回毫秒延迟或错误。 */
    public DataSourceTestResponse test(String kind, Map<String, Object> cfg) {
        long t0 = System.currentTimeMillis();
        try (HikariDataSource ds = (HikariDataSource) buildTempDataSource(kind, cfg);
             Connection conn = ds.getConnection()) {
            boolean ok = conn.isValid(2);
            int latency = (int) (System.currentTimeMillis() - t0);
            return ok
                    ? new DataSourceTestResponse(true, "ok", latency)
                    : new DataSourceTestResponse(false, "isValid 返回 false", latency);
        } catch (Exception e) {
            log.warn("[ds] jdbc test failed: {}", e.toString());
            return new DataSourceTestResponse(false, e.getMessage(), null);
        }
    }

    /** 列出当前 catalog/schema 下的所有用户表。MySQL 读 information_schema；PgSQL 读 pg_catalog。 */
    public List<String> listTables(String kind, Map<String, Object> cfg) {
        try (HikariDataSource ds = (HikariDataSource) buildTempDataSource(kind, cfg);
             Connection conn = ds.getConnection()) {
            String sql;
            if ("mysql".equals(kind)) {
                sql = "SELECT TABLE_NAME FROM information_schema.tables " +
                      "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME";
            } else {
                sql = "SELECT tablename FROM pg_catalog.pg_tables " +
                      "WHERE schemaname NOT IN ('pg_catalog','information_schema') " +
                      "ORDER BY tablename";
            }
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("列出表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 内省整库 schema：表 + 表注释 + 列 + 列注释 + 主键 + 外键 + 唯一约束 + 行数估算。
     * <p>这是构造本体血缘图的"原料"——没有列/FK，LLM 只能瞎猜。
     * <p>实现要点：所有元数据查 information_schema（MySQL）/ pg_catalog（PgSQL），
     * 单次连接一次性查完，避免对每张表打 N 次 query。
     */
    public DatabaseSchemaInfo introspectSchema(String kind, Map<String, Object> cfg, int tableLimit) {
        int safeLimit = tableLimit <= 0 ? 200 : Math.min(tableLimit, 500);
        String dbName = strOf(cfg, "database", "");
        try (HikariDataSource ds = (HikariDataSource) buildTempDataSource(kind, cfg);
             Connection conn = ds.getConnection()) {
            if ("mysql".equals(kind)) {
                return introspectMysql(conn, dbName, safeLimit);
            }
            if ("pgsql".equals(kind)) {
                return introspectPgsql(conn, dbName, safeLimit);
            }
            throw new IllegalArgumentException("不支持的 kind: " + kind);
        } catch (SQLException e) {
            throw new IllegalStateException("内省 schema 失败: " + e.getMessage(), e);
        }
    }

    /** MySQL schema 内省：用 information_schema 一次性拉完表/列/键/FK。 */
    private DatabaseSchemaInfo introspectMysql(Connection conn, String dbName, int tableLimit) throws SQLException {
        // 1) 表 + 注释 + 行数估算
        Map<String, TableBuilder> tables = new LinkedHashMap<>();
        String tablesSql = """
                SELECT TABLE_NAME, IFNULL(TABLE_COMMENT,''), IFNULL(TABLE_ROWS,0)
                FROM information_schema.tables
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE='BASE TABLE'
                ORDER BY TABLE_NAME
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(tablesSql)) {
            ps.setInt(1, tableLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String comment = rs.getString(2);
                    long rows = rs.getLong(3);
                    tables.put(name, new TableBuilder(name, comment, rows));
                }
            }
        }
        if (tables.isEmpty()) {
            return new DatabaseSchemaInfo("mysql", dbName, List.of());
        }

        // 2) 列：name, type, nullable, default, comment, ordinal
        String colsSql = """
                SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
                       IFNULL(COLUMN_DEFAULT,''), IFNULL(COLUMN_COMMENT,''),
                       ORDINAL_POSITION, COLUMN_KEY
                FROM information_schema.columns
                WHERE TABLE_SCHEMA = DATABASE()
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """;
        try (PreparedStatement ps = conn.prepareStatement(colsSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String t = rs.getString(1);
                TableBuilder tb = tables.get(t);
                if (tb == null) continue;
                ColumnInfo ci = new ColumnInfo(
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

        // 3) 外键：information_schema.key_column_usage 里 REFERENCED_TABLE_NAME 非空的行
        String fkSql = """
                SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME,
                       REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
                FROM information_schema.key_column_usage
                WHERE TABLE_SCHEMA = DATABASE()
                  AND REFERENCED_TABLE_NAME IS NOT NULL
                ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION
                """;
        try (PreparedStatement ps = conn.prepareStatement(fkSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String t = rs.getString(1);
                TableBuilder tb = tables.get(t);
                if (tb == null) continue;
                tb.foreignKeys.add(new ForeignKeyInfo(
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5)));
            }
        }

        // 4) 唯一约束（去掉 PRIMARY，因为已经在 columns.primaryKey 标了）
        String uniqSql = """
                SELECT s.TABLE_NAME, s.INDEX_NAME, s.COLUMN_NAME, s.SEQ_IN_INDEX
                FROM information_schema.statistics s
                WHERE s.TABLE_SCHEMA = DATABASE()
                  AND s.NON_UNIQUE = 0
                  AND s.INDEX_NAME <> 'PRIMARY'
                ORDER BY s.TABLE_NAME, s.INDEX_NAME, s.SEQ_IN_INDEX
                """;
        try (PreparedStatement ps = conn.prepareStatement(uniqSql);
             ResultSet rs = ps.executeQuery()) {
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
                tables.get(table).uniqueKeys.add(new UniqueKeyInfo(idxName, e.getValue()));
            }
        }

        return new DatabaseSchemaInfo("mysql", dbName,
                tables.values().stream().map(TableBuilder::build).toList());
    }

    /** PgSQL schema 内省：用 pg_catalog + information_schema 拼装。 */
    private DatabaseSchemaInfo introspectPgsql(Connection conn, String dbName, int tableLimit) throws SQLException {
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
            return new DatabaseSchemaInfo("pgsql", dbName, List.of());
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
                tb.columns.add(new ColumnInfo(
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
                tb.foreignKeys.add(new ForeignKeyInfo(
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
                tables.get(table).uniqueKeys.add(new UniqueKeyInfo(idxName, e.getValue()));
            }
        }

        return new DatabaseSchemaInfo("pgsql", dbName,
                tables.values().stream().map(TableBuilder::build).toList());
    }

    /** 内部 builder：边拉数据边累加，最后 build() 成不可变 TableInfo。 */
    private static final class TableBuilder {
        final String name;
        final String comment;
        final long rows;
        final List<ColumnInfo> columns = new ArrayList<>();
        final List<ForeignKeyInfo> foreignKeys = new ArrayList<>();
        final List<UniqueKeyInfo> uniqueKeys = new ArrayList<>();

        TableBuilder(String name, String comment, long rows) {
            this.name = name;
            this.comment = comment;
            this.rows = rows;
        }

        TableInfo build() {
            return new TableInfo(name, comment, columns, foreignKeys, uniqueKeys, rows);
        }
    }

    /** 预览：SELECT * FROM `<table>` LIMIT N。table 名走白名单校验防 SQL 注入。 */
    public TablePreviewResponse previewTable(String kind, Map<String, Object> cfg,
                                             String table, int limit) {
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, MAX_LIMIT));
        String quoted = "mysql".equals(kind) ? "`" + table + "`" : "\"" + table + "\"";
        String sql = "SELECT * FROM " + quoted + " LIMIT " + safeLimit;
        SqlExecuteResponse r = executeSql(kind, cfg, sql, safeLimit);
        TablePreviewResponse out = new TablePreviewResponse();
        out.setColumns(r.getColumns());
        out.setRows(r.getRows());
        out.setRowCount(r.getRowCount());
        out.setTruncated(r.isTruncated());
        return out;
    }

    /** 执行只读 SQL：白名单校验 → 单语句校验 → 强制 LIMIT 兜底 → 受限超时执行。 */
    public SqlExecuteResponse executeSql(String kind, Map<String, Object> cfg,
                                         String rawSql, int limit) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new IllegalArgumentException("SQL 为空");
        }
        // 切掉末尾分号 + 拒绝多语句
        String sql = rawSql.trim();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
        if (sql.contains(";")) {
            throw new IllegalArgumentException("不允许多语句执行");
        }
        if (!READONLY_PATTERN.matcher(sql).matches()) {
            throw new IllegalArgumentException("仅允许只读查询语句 (SELECT/SHOW/DESC/EXPLAIN)");
        }
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        // SELECT 类语句没有 LIMIT 时追加
        boolean isSelect = sql.toUpperCase().startsWith("SELECT");
        boolean hasLimit = sql.toUpperCase().contains(" LIMIT ");
        if (isSelect && !hasLimit) sql = sql + " LIMIT " + safeLimit;

        long t0 = System.currentTimeMillis();
        try (HikariDataSource ds = (HikariDataSource) buildTempDataSource(kind, cfg);
             Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> cols = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) cols.add(meta.getColumnLabel(i));
                List<List<Object>> rows = new ArrayList<>();
                int n = 0;
                boolean truncated = false;
                while (rs.next()) {
                    if (n >= safeLimit) { truncated = true; break; }
                    List<Object> row = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        Object v = rs.getObject(i);
                        row.add(v == null ? null : (isSimple(v) ? v : String.valueOf(v)));
                    }
                    rows.add(row);
                    n++;
                }
                SqlExecuteResponse r = new SqlExecuteResponse();
                r.setColumns(cols);
                r.setRows(rows);
                r.setRowCount(rows.size());
                r.setDurationMs((int) (System.currentTimeMillis() - t0));
                r.setTruncated(truncated);
                return r;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 执行失败: " + e.getMessage(), e);
        }
    }

    private static boolean isSimple(Object v) {
        return v instanceof Number || v instanceof Boolean || v instanceof String;
    }
}

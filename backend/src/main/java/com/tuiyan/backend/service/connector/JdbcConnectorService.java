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

    // 危险函数/子句黑名单：即便以 SELECT 开头，也可能读写后端文件或跨库（绕过只读语义），一律拦截
    private static final Pattern DANGEROUS_SQL_PATTERN =
            Pattern.compile("\\b(INTO\\s+OUTFILE|INTO\\s+DUMPFILE|LOAD_FILE|LOAD\\s+DATA"
                    + "|pg_read_file|pg_read_binary_file|pg_ls_dir|pg_stat_file"
                    + "|lo_import|lo_export|dblink|COPY|sys_exec|sys_eval|xp_cmdshell)\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // 方言专用内省协作类：introspectSchema 按 kind 委派给它们
    private final MysqlSchemaIntrospector mysqlSchemaIntrospector;
    private final PgsqlSchemaIntrospector pgsqlSchemaIntrospector;
    private final OracleSchemaIntrospector oracleSchemaIntrospector;

    public JdbcConnectorService(MysqlSchemaIntrospector mysqlSchemaIntrospector,
                                PgsqlSchemaIntrospector pgsqlSchemaIntrospector,
                                OracleSchemaIntrospector oracleSchemaIntrospector) {
        this.mysqlSchemaIntrospector = mysqlSchemaIntrospector;
        this.pgsqlSchemaIntrospector = pgsqlSchemaIntrospector;
        this.oracleSchemaIntrospector = oracleSchemaIntrospector;
    }

    /** 行数限制子句：Oracle 用 {@code FETCH FIRST n ROWS ONLY}，MySQL/PgSQL 用 {@code LIMIT n}。 */
    private static String rowLimitClause(String kind, int n) {
        return "oracle".equals(kind) ? " FETCH FIRST " + n + " ROWS ONLY" : " LIMIT " + n;
    }

    /** SQL 是否已自带行数限制（避免重复追加；upperSql 须为大写）。 */
    private static boolean hasRowLimit(String kind, String upperSql) {
        if ("oracle".equals(kind)) {
            return upperSql.contains("FETCH FIRST") || upperSql.contains("FETCH NEXT") || upperSql.contains("ROWNUM");
        }
        return upperSql.contains(" LIMIT ");
    }

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

    /** 单表元信息：表名 + 注释 + 列 + 外键 + 唯一约束 + 估算行数 + 类型(table/view) + 视图定义体 */
    public record TableInfo(String name,
                            String comment,
                            List<ColumnInfo> columns,
                            List<ForeignKeyInfo> foreignKeys,
                            List<UniqueKeyInfo> uniqueKeys,
                            Long estimatedRows,
                            String kind,
                            String definition) {
        /** 是否视图（含物化视图）。视图的 {@link #definition} 是血缘 ground truth。 */
        public boolean isView() { return "view".equalsIgnoreCase(kind); }
    }

    /** 整库 schema：kind + database + 全表 */
    public record DatabaseSchemaInfo(String kind,
                                     String database,
                                     List<TableInfo> tables) {}

    /** 按 kind + config 构造一个独立、最小化的 HikariDataSource，调用方负责关。 */
    private DataSource buildTempDataSource(String kind, Map<String, Object> cfg) {
        String host = strOf(cfg, "host", "localhost");
        Object portObj = cfg.get("port");
        int port = portObj instanceof Number n ? n.intValue() : switch (kind) {
            case "mysql" -> 3306;
            case "oracle" -> 1521;
            default -> 5432;
        };
        String db = strOf(cfg, "database", "");
        String user = strOf(cfg, "username", "");
        String pwd = strOf(cfg, "password", "");
        String params = sanitizeJdbcParams(strOf(cfg, "params", ""));

        String url;
        String driver;
        if ("mysql".equals(kind)) {
            // 强制关闭本地文件加载/反序列化（防 JDBC 攻击：恶意服务器读取后端文件或反序列化 RCE）
            String hardened = "allowLoadLocalInfile=false&allowUrlInLocalInfile=false&autoDeserialize=false";
            url = "jdbc:mysql://" + host + ":" + port + "/" + db + "?" + hardened
                    + (params.isBlank() ? "" : "&" + params);
            driver = "com.mysql.cj.jdbc.Driver";
        } else if ("pgsql".equals(kind)) {
            url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
            if (!params.isBlank()) url += "?" + params;
            driver = "org.postgresql.Driver";
        } else if ("oracle".equals(kind)) {
            // thin 驱动 service-name 形式：database 字段填服务名（或 SID）。thin URL 不接受 ?k=v 查询参数，故忽略 params。
            url = "jdbc:oracle:thin:@//" + host + ":" + port + "/" + db;
            driver = "oracle.jdbc.OracleDriver";
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

    /**
     * 用户自定义 JDBC 连接参数（"k1=v1&k2=v2"）剥离危险键，防 JDBC 攻击。
     * <p>已知可被恶意 MySQL/PG 服务器利用读取后端本地文件 / 触发反序列化 RCE 的驱动参数一律丢弃；
     * 其余如 useSSL / serverTimezone / characterEncoding 等正常参数原样保留。
     */
    private static final java.util.Set<String> DANGEROUS_JDBC_PARAMS = java.util.Set.of(
            "allowloadlocalinfile", "allowurlinlocalinfile", "uselocalinfile", "allowlocalinfile",
            "autodeserialize", "queryinterceptors", "statementinterceptors",
            "detectcustomcollations", "allowmultiqueries", "loggerclassname", "profilersqlclass",
            "servercertificate", "clientinfoprovider", "socketfactory", "authenticationplugins");

    static String sanitizeJdbcParams(String raw) {
        if (raw == null || raw.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            String key = (eq >= 0 ? pair.substring(0, eq) : pair).trim().toLowerCase(java.util.Locale.ROOT);
            if (DANGEROUS_JDBC_PARAMS.contains(key)) continue; // 丢弃危险参数
            if (out.length() > 0) out.append('&');
            out.append(pair.trim());
        }
        return out.toString();
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
            } else if ("oracle".equals(kind)) {
                // 当前登录用户(schema)下的表
                sql = "SELECT table_name FROM user_tables ORDER BY table_name";
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
                return mysqlSchemaIntrospector.introspect(conn, dbName, safeLimit);
            }
            if ("pgsql".equals(kind)) {
                return pgsqlSchemaIntrospector.introspect(conn, dbName, safeLimit);
            }
            if ("oracle".equals(kind)) {
                return oracleSchemaIntrospector.introspect(conn, dbName, safeLimit);
            }
            throw new IllegalArgumentException("不支持的 kind: " + kind);
        } catch (SQLException e) {
            throw new IllegalStateException("内省 schema 失败: " + e.getMessage(), e);
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
        String sql = "SELECT * FROM " + quoted + rowLimitClause(kind, safeLimit);
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
        if (DANGEROUS_SQL_PATTERN.matcher(sql).find()) {
            // 以 SELECT 开头但可读写后端文件 / 跨库的危险函数（绕过只读语义），一律拒绝
            throw new IllegalArgumentException("SQL 含禁止的文件/系统操作（如 INTO OUTFILE / pg_read_file 等）");
        }
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        // SELECT 类语句没有行数限制时按方言追加（MySQL/PgSQL: LIMIT；Oracle: FETCH FIRST）
        boolean isSelect = sql.toUpperCase().startsWith("SELECT");
        boolean hasLimit = hasRowLimit(kind, sql.toUpperCase());
        if (isSelect && !hasLimit) sql = sql + rowLimitClause(kind, safeLimit);

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

    /** 单表样例数据：列名 + 若干行(单元格为简单值,复杂类型已转字符串)。 */
    public record TableSample(List<String> columns, List<List<Object>> rows) {}

    /**
     * 为「导出 DDL 到经验库」附带少量样例数据：复用同一条连接,对每张<b>基表</b>(跳过视图)
     * 取前 N 行。只读 SELECT、带查询超时与 setMaxRows;单表取数失败(如无权限)静默跳过,不影响导出。
     * @param tables    内省得到的表(其 name 可能是 pgsql 的 schema.table 限定名)
     * @param perTable  每表取多少行(夹到 1..20)
     * @param maxTables 最多采样多少张表,防大库打太多查询
     * @return 表名 → 样例;keyed by {@link TableInfo#name()},按入参顺序保留
     */
    public Map<String, TableSample> sampleRows(String kind, Map<String, Object> cfg,
                                               List<TableInfo> tables, int perTable, int maxTables) {
        int n = Math.max(1, Math.min(perTable, 20));
        int capTables = Math.max(1, maxTables);
        Map<String, TableSample> out = new LinkedHashMap<>();
        try (HikariDataSource ds = (HikariDataSource) buildTempDataSource(kind, cfg);
             Connection conn = ds.getConnection()) {
            int used = 0;
            for (TableInfo t : tables) {
                if (t.isView()) continue;            // 视图取数可能很重,只采基表
                if (used >= capTables) break;
                used++;
                String sql = "SELECT * FROM " + quotedRef(kind, t.name()) + rowLimitClause(kind, n);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
                    ps.setMaxRows(n);
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();
                        List<String> cols = new ArrayList<>(colCount);
                        for (int i = 1; i <= colCount; i++) cols.add(meta.getColumnLabel(i));
                        List<List<Object>> rows = new ArrayList<>();
                        while (rs.next() && rows.size() < n) {
                            List<Object> row = new ArrayList<>(colCount);
                            for (int i = 1; i <= colCount; i++) {
                                Object v = rs.getObject(i);
                                row.add(v == null ? null : (isSimple(v) ? v : String.valueOf(v)));
                            }
                            rows.add(row);
                        }
                        if (!rows.isEmpty()) out.put(t.name(), new TableSample(cols, rows));
                    }
                } catch (SQLException ex) {
                    log.debug("[ds] 样例采集跳过表 {}: {}", t.name(), ex.getMessage());
                }
            }
        } catch (SQLException e) {
            log.warn("[ds] 样例采集失败(整体跳过): {}", e.getMessage());
        }
        return out;
    }

    /** 把表名(可能是 pgsql 的 schema.table)安全地加引号:mysql 反引号,pgsql 双引号并按点拆分。 */
    private static String quotedRef(String kind, String name) {
        if ("mysql".equals(kind)) return "`" + name.replace("`", "``") + "`";
        int dot = name.indexOf('.');
        if (dot > 0) {
            String sch = name.substring(0, dot), tbl = name.substring(dot + 1);
            return "\"" + sch.replace("\"", "\"\"") + "\".\"" + tbl.replace("\"", "\"\"") + "\"";
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}

package com.tuiyan.backend.service.connector;

import java.util.Set;

/**
 * 数据源类型(kind)的集中定义。
 * <p>用常量类而非枚举：库里已持久化裸字符串 kind，且 HTTP/文件类数据源与 JDBC 方言并不同质，
 * 常量 + 集合的形态比枚举更贴合「按集合判定归属」的用法，也对历史数据零影响。
 * <p>新增一种 JDBC 数据库：在此加常量、并入 {@link #JDBC}，再实现一个
 * {@link SqlDialect}（{@code @Component}）即可——调度层 {@link JdbcConnectorService} 与
 * {@link com.tuiyan.backend.service.DataSourceService} 均无需改动。
 */
public final class SourceKind {

    private SourceKind() {}

    // ── JDBC 关系型数据库 ──
    public static final String MYSQL = "mysql";
    public static final String PGSQL = "pgsql";
    public static final String ORACLE = "oracle";
    public static final String DM = "dm";       // 达梦（兼容 Oracle 数据字典）
    public static final String GBASE = "gbase";  // GBase 8a（走 MySQL 线协议）

    // ── 非 JDBC 数据源 ──
    public static final String HTTPS_API = "https_api";
    public static final String FILE_STORED = "file_stored";

    /** 走 {@link JdbcConnectorService} 的关系型数据库集合。 */
    public static final Set<String> JDBC = Set.of(MYSQL, PGSQL, ORACLE, DM, GBASE);

    /** 允许创建的全部数据源类型（替换原 DataSourceService.ALLOWED_KINDS）。 */
    public static final Set<String> CREATABLE =
            Set.of(MYSQL, PGSQL, ORACLE, DM, GBASE, HTTPS_API);

    /** 是否关系型数据库（走 JDBC 连接器）。 */
    public static boolean isJdbc(String kind) {
        return JDBC.contains(kind);
    }
}

package com.tuiyan.backend.service.connector;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 单一关系型数据库方言的连接 + 内省策略。
 *
 * <p>这是从 {@link JdbcConnectorService} 抽出的可扩展点，与
 * {@link com.tuiyan.backend.service.extraction.SourceFileHandler} 同构：新增一种数据库
 * 只需新增一个 {@code @Component} 实现并在 {@link SourceKind} 注册 kind，
 * {@link JdbcConnectorService} 与 {@link SqlDialectRegistry} 无需任何改动——遵循开闭原则。
 *
 * <p>方言之间的复用通过继承表达：如 GBase 走 MySQL 线协议，{@code GbaseDialect extends MysqlDialect}
 * 只覆写 {@link #kind()}；达梦兼容 Oracle 数据字典，{@code DmDialect extends OracleDialect} 同理。
 */
public interface SqlDialect {

    /** 本方言是否处理该 kind（注册键，通常精确 equals）。 */
    boolean supports(String kind);

    /** 内省结果回填的真实 kind（gbase/dm 在此与其父方言区分）。 */
    String kind();

    /** 未显式配置端口时的默认端口。 */
    int defaultPort();

    /** 构造 JDBC URL（host/port/database/已脱敏的连接参数 params）。 */
    String jdbcUrl(String host, int port, String database, String params);

    /** JDBC 驱动类名。 */
    String driverClass();

    /**
     * 给标识符安全加引号。{@code name} 可能是 {@code schema.table}（pgsql），实现需按点拆分后分别加引号。
     * MySQL 系用反引号，其余用双引号。
     */
    String quote(String name);

    /** 行数限制子句：MySQL/PgSQL 用 {@code LIMIT n}，Oracle 系用 {@code FETCH FIRST n ROWS ONLY}。 */
    String rowLimitClause(int n);

    /** 判断（已转大写的）SQL 是否已自带行数限制，避免重复追加。 */
    boolean hasRowLimit(String upperSql);

    /** 列出当前 catalog/schema 下全部用户表的 SQL（单列：表名）。 */
    String listTablesSql();

    /**
     * 整库 schema 内省：表 + 列 + 主键 + 外键 + 唯一约束 + 注释 + 行数估算。
     * 返回结果的 {@code kind} 字段须为 {@link #kind()}。
     */
    JdbcConnectorService.DatabaseSchemaInfo introspect(Connection conn, String database, int tableLimit) throws SQLException;
}

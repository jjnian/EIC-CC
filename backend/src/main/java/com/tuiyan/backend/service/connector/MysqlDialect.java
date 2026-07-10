package com.tuiyan.backend.service.connector;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL 方言：information_schema 内省、反引号、LIMIT。
 * <p>GBase 8a 走 MySQL 线协议，由 {@link GbaseDialect} 继承本类、仅覆写 kind。
 */
@Component
public class MysqlDialect extends AbstractSqlDialect {

    // 强制关闭本地文件加载/反序列化（防 JDBC 攻击：恶意服务器读后端文件或反序列化 RCE）
    private static final String HARDENED =
            "allowLoadLocalInfile=false&allowUrlInLocalInfile=false&autoDeserialize=false";

    @Override
    public String kind() {
        return SourceKind.MYSQL;
    }

    @Override
    public int defaultPort() {
        return 3306;
    }

    @Override
    public String jdbcUrl(String host, int port, String database, String params) {
        return "jdbc:mysql://" + host + ":" + port + "/" + database + "?" + HARDENED
                + (params == null || params.isBlank() ? "" : "&" + params);
    }

    @Override
    public String driverClass() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public String quote(String name) {
        return "`" + name.replace("`", "``") + "`";
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
        return "SELECT TABLE_NAME FROM information_schema.tables " +
               "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME";
    }

    @Override
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
            return build(dbName, tables);
        }
        // 后续查询都按首查拿到的表清单收敛,表很多(超出 limit)的库不再全库扫列/键
        List<String> names = new ArrayList<>(tables.keySet());
        String in = inPlaceholders(names.size());

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
                    tb.columns.add(new JdbcConnectorService.ColumnInfo(
                            rs.getString(2),
                            rs.getString(3),
                            "YES".equalsIgnoreCase(rs.getString(4)),
                            rs.getString(5),
                            rs.getString(6),
                            "PRI".equalsIgnoreCase(rs.getString(8)),
                            rs.getInt(7)));
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
                UniqueKeyAccumulator acc = new UniqueKeyAccumulator();
                while (rs.next()) {
                    String t = rs.getString(1);
                    if (!tables.containsKey(t)) continue;
                    acc.add(t, rs.getString(2), rs.getString(3));
                }
                acc.flushTo(tables);
            }
        }

        // 5) 存储过程/函数定义体（ETL 血缘 ground truth）。ROUTINE_DEFINITION 无权限时为 NULL；
        //    GBase 8a 等兼容库可能缺 routines 字典——整体失败静默降级，不影响表内省。
        List<JdbcConnectorService.RoutineInfo> routines = new ArrayList<>();
        String routineSql = """
                SELECT ROUTINE_NAME, ROUTINE_TYPE, IFNULL(ROUTINE_COMMENT,''), IFNULL(ROUTINE_DEFINITION,'')
                FROM information_schema.routines
                WHERE ROUTINE_SCHEMA = DATABASE()
                ORDER BY ROUTINE_NAME
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(routineSql)) {
            ps.setInt(1, MAX_ROUTINES);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    routines.add(routineOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            // 无权限 / 方言不支持：跳过存储过程，表结构照常返回
        }

        // 5.5) 事件调度器：EVENT_DEFINITION 是库内定时 ETL 的 ground truth。以 RoutineInfo(kind=event)
        //      承载，comment 带调度周期——血缘解析与 DDL 节选完全复用存储过程通路。
        //      GBase 8a 等兼容库可能缺 EVENTS 字典，整体失败静默降级。
        String eventSql = """
                SELECT EVENT_NAME,
                       IFNULL(EVENT_COMMENT,''),
                       IFNULL(EVENT_DEFINITION,''),
                       IFNULL(EXECUTE_AT,''),
                       IFNULL(CONCAT('每 ', INTERVAL_VALUE, ' ', INTERVAL_FIELD),''),
                       STATUS
                FROM information_schema.EVENTS
                WHERE EVENT_SCHEMA = DATABASE()
                ORDER BY EVENT_NAME
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(eventSql)) {
            ps.setInt(1, MAX_ROUTINES);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String schedule = rs.getString(5) == null || rs.getString(5).isBlank()
                            ? ("定时 " + rs.getString(4)) : rs.getString(5);
                    String comment = schedule
                            + ("ENABLED".equalsIgnoreCase(rs.getString(6)) ? "" : "（已停用）")
                            + (rs.getString(2).isBlank() ? "" : "：" + rs.getString(2));
                    routines.add(routineOf(rs.getString(1), "event", comment, rs.getString(3)));
                }
            }
        } catch (SQLException e) {
            // 无权限 / 无 EVENTS 字典：跳过事件调度器
        }

        // 6) 触发器：ACTION_STATEMENT 是完整触发器体，EVENT_OBJECT_TABLE 是挂载表——
        //    审计/同步表的写入血缘由「挂载表 → 触发器体写入目标」推出。
        List<JdbcConnectorService.TriggerInfo> triggers = new ArrayList<>();
        String trgSql = """
                SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE,
                       CONCAT(ACTION_TIMING, ' ', EVENT_MANIPULATION),
                       IFNULL(ACTION_STATEMENT,'')
                FROM information_schema.TRIGGERS
                WHERE TRIGGER_SCHEMA = DATABASE()
                ORDER BY EVENT_OBJECT_TABLE, TRIGGER_NAME
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(trgSql)) {
            ps.setInt(1, MAX_TRIGGERS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    triggers.add(triggerOf(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            // 无权限：跳过触发器
        }

        return build(dbName, tables, routines, triggers, List.of());
    }
}

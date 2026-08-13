package com.tuiyan.backend.service.connector;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 方言内省的公共基类：下沉三个内省器里反复出现的样板——
 * IN 子句构造、占位符批量绑定、唯一键「按索引名聚合多列」的累加模式、空表早退。
 * <p>各方言子类只需实现自己的 SQL 与列映射，无需再重复这些骨架。
 */
public abstract class AbstractSqlDialect implements SqlDialect {

    @Override
    public boolean supports(String kind) {
        return kind() != null && kind().equals(kind);
    }

    /** 构造 {@code (?, ?, ...)} 占位符串，用于 IN 子句。 */
    protected static String inPlaceholders(int n) {
        return "(" + String.join(",", Collections.nCopies(n, "?")) + ")";
    }

    /** 按顺序把名字绑进 IN 子句的占位符。 */
    protected static void bindAll(PreparedStatement ps, List<String> names) throws SQLException {
        for (int i = 0; i < names.size(); i++) ps.setString(i + 1, names.get(i));
    }

    /**
     * 唯一键聚合器：把「(tableKey, indexName, columnName) 多行」按索引归并成「每个唯一键一组有序列」，
     * 收尾时回填到对应 TableBuilder。三个内省器原先各写了一份近乎相同的实现，在此统一。
     */
    protected static final class UniqueKeyAccumulator {
        // key = tableKey::indexName -> 该唯一键的有序列
        private final Map<String, List<String>> cols = new LinkedHashMap<>();
        private final Map<String, String> keyToTable = new HashMap<>();

        /** 累加一行：tableKey 为 tables map 的键，indexName 为唯一索引/约束名，column 为列名。 */
        public void add(String tableKey, String indexName, String column) {
            String k = tableKey + "::" + indexName;
            cols.computeIfAbsent(k, x -> new ArrayList<>()).add(column);
            keyToTable.put(k, tableKey);
        }

        /** 把聚合结果回填到 tables：对每个唯一键创建一条 UniqueKeyInfo。 */
        public void flushTo(Map<String, TableBuilder> tables) {
            for (Map.Entry<String, List<String>> e : cols.entrySet()) {
                String tableKey = keyToTable.get(e.getKey());
                TableBuilder tb = tables.get(tableKey);
                if (tb == null) continue;
                String idxName = e.getKey().substring(tableKey.length() + 2);
                tb.uniqueKeys.add(new JdbcConnectorService.UniqueKeyInfo(idxName, e.getValue()));
            }
        }
    }

    /** 单库最多内省的存储过程/函数数：防过程极多的库拖慢内省、撑爆经验正文。 */
    protected static final int MAX_ROUTINES = 200;
    /** 单个过程定义体的截断长度：血缘解析与 DDL 节选都用不到更长的源码。 */
    protected static final int MAX_ROUTINE_DEF_CHARS = 60_000;

    /** 单库最多内省的触发器数：防触发器极多的库拖慢内省。 */
    protected static final int MAX_TRIGGERS = 200;

    /** 构造 RoutineInfo：kind 统一小写，定义体按 {@link #MAX_ROUTINE_DEF_CHARS} 截断，null 安全。 */
    protected static JdbcConnectorService.RoutineInfo routineOf(String name, String kind,
                                                                String comment, String definition) {
        String def = definition == null ? "" : definition;
        if (def.length() > MAX_ROUTINE_DEF_CHARS) def = def.substring(0, MAX_ROUTINE_DEF_CHARS);
        return new JdbcConnectorService.RoutineInfo(
                name,
                kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT),
                comment == null ? "" : comment,
                def);
    }

    /** 构造 TriggerInfo：触发器体按 {@link #MAX_ROUTINE_DEF_CHARS} 截断，null 安全。 */
    protected static JdbcConnectorService.TriggerInfo triggerOf(String name, String table,
                                                                String timing, String body) {
        String def = body == null ? "" : body;
        if (def.length() > MAX_ROUTINE_DEF_CHARS) def = def.substring(0, MAX_ROUTINE_DEF_CHARS);
        return new JdbcConnectorService.TriggerInfo(
                name, table == null ? "" : table, timing == null ? "" : timing.trim(), def);
    }

    /** 把 TableBuilder map 收尾成不可变的 DatabaseSchemaInfo，kind 取本方言的 {@link #kind()}。 */
    protected JdbcConnectorService.DatabaseSchemaInfo build(String database, Map<String, TableBuilder> tables) {
        return build(database, tables, List.of());
    }

    /** 同上，附带内省到的存储过程/函数。 */
    protected JdbcConnectorService.DatabaseSchemaInfo build(String database, Map<String, TableBuilder> tables,
                                                            List<JdbcConnectorService.RoutineInfo> routines) {
        return build(database, tables, routines, List.of(), List.of());
    }

    /** 同上，附带触发器与目录级对象依赖。 */
    protected JdbcConnectorService.DatabaseSchemaInfo build(String database, Map<String, TableBuilder> tables,
                                                            List<JdbcConnectorService.RoutineInfo> routines,
                                                            List<JdbcConnectorService.TriggerInfo> triggers,
                                                            List<JdbcConnectorService.DependencyInfo> dependencies) {
        return new JdbcConnectorService.DatabaseSchemaInfo(
                kind(), database,
                tables.values().stream().map(TableBuilder::build).toList(),
                routines, triggers, dependencies);
    }
}

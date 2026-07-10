package com.tuiyan.backend.support;

import com.tuiyan.backend.service.connector.JdbcConnectorService.DatabaseSchemaInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.RoutineInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.TableInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 从内省 schema 的「SQL 定义体」——视图定义与存储过程/函数源码——确定性解析表级血缘。
 * <p>视图定义（FROM/JOIN 的来源表 → 视图）与过程源码（INSERT…SELECT / MERGE / UPDATE…FROM
 * 的来源 → 写入目标）是数据库里现成的 ETL 血缘 ground truth，复用 {@link SqlLineageExtractor}
 * 的语句级解析直出数据流，不经 LLM、置信度 1.0。
 * <p>两个消费方共享同一份解析：结构建图（StructuralGraphService）与 DDL 经验的结构化片段
 * （SchemaGraphFragmentRenderer），保证两条建图路径的确定性血缘口径一致。
 */
public final class SchemaSqlLineage {

    private SchemaSqlLineage() {}

    /**
     * 一条对象级数据流：source 表 → target 表/视图。
     * @param via      载体说明（如「视图 v_x」「存储过程 sync_orders」），可直接做边 label
     * @param evidence 证据文案（载体 + 语句类型）
     */
    public record ObjectFlow(String source, String target, String via, String evidence) {}

    /** 视图定义 → 「来源表 → 视图」数据流。定义体缺失或解析失败的视图静默跳过。 */
    public static List<ObjectFlow> viewFlows(DatabaseSchemaInfo schema) {
        List<ObjectFlow> out = new ArrayList<>();
        if (schema == null || schema.tables() == null) return out;
        for (TableInfo t : schema.tables()) {
            if (!t.isView() || t.definition() == null || t.definition().isBlank()) continue;
            SqlLineageExtractor.Result r;
            try {
                r = SqlLineageExtractor.parse("CREATE VIEW " + t.name() + " AS " + t.definition());
            } catch (RuntimeException ex) {
                continue;   // 单个视图解析失败不影响其余
            }
            for (SqlLineageExtractor.Flow f : r.flows()) {
                out.add(new ObjectFlow(f.source(), t.name(), "视图 " + t.name(), "视图定义(SQL)"));
            }
        }
        return out;
    }

    /**
     * 存储过程/函数/定时任务源码 → 「来源表 → 写入目标表」数据流。源码缺失或解析失败的对象静默跳过。
     * 定时任务（MySQL event / Oracle job）的调度周期在 comment 里，一并写进证据——
     * 「每天跑的 INSERT…SELECT」对业务方就是排产/同步链路本身。
     */
    public static List<ObjectFlow> routineFlows(DatabaseSchemaInfo schema) {
        List<ObjectFlow> out = new ArrayList<>();
        if (schema == null || schema.routines() == null) return out;
        for (RoutineInfo rt : schema.routines()) {
            if (rt.definition() == null || rt.definition().isBlank()) continue;
            SqlLineageExtractor.Result r;
            try {
                r = SqlLineageExtractor.parse(rt.definition());
            } catch (RuntimeException ex) {
                continue;
            }
            String label = routineKindLabel(rt.kind()) + " " + rt.name();
            String schedule = rt.comment() == null || rt.comment().isBlank()
                    ? "" : "（" + truncate(rt.comment(), 40) + "）";
            for (SqlLineageExtractor.Flow f : r.flows()) {
                out.add(new ObjectFlow(f.source(), f.target(), label,
                        label + schedule + " · " + f.kind()));
            }
        }
        return out;
    }

    /**
     * 触发器 → 数据流。两路产出：
     * <ol>
     *   <li>触发器体里的显式流（UPDATE…FROM 等有来源表的语句）走常规 {@code parse}；</li>
     *   <li><b>挂载表 → 写入目标</b>：触发器体常写 {@code INSERT INTO audit VALUES(NEW.*)}——
     *       数据来源是 NEW/OLD 行、语句里没有 FROM，来源即挂载表本身，用
     *       {@link SqlLineageExtractor#writeTargets} 探测写入目标后补上这层隐含流。</li>
     * </ol>
     */
    public static List<ObjectFlow> triggerFlows(DatabaseSchemaInfo schema) {
        List<ObjectFlow> out = new ArrayList<>();
        if (schema == null || schema.triggers() == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();   // 显式流与隐含流可能给出同一对 (src,dst)
        for (var trg : schema.triggers()) {
            if (trg.body() == null || trg.body().isBlank() || trg.table() == null || trg.table().isBlank()) continue;
            String label = "触发器 " + trg.name();
            String timing = trg.timing() == null || trg.timing().isBlank() ? "" : "（" + trg.timing() + "）";
            try {
                // 显式流：语句里自带来源表的（如 UPDATE stats SET … FROM orders）
                for (SqlLineageExtractor.Flow f : SqlLineageExtractor.parse(trg.body()).flows()) {
                    if (!seen.add(key(trg.name(), f.source(), f.target()))) continue;
                    out.add(new ObjectFlow(f.source(), f.target(), label, label + timing + " · " + f.kind()));
                }
                // 隐含流：挂载表 → 触发器体的写入目标（NEW/OLD 行即来源）
                for (SqlLineageExtractor.WriteTarget wt : SqlLineageExtractor.writeTargets(trg.body())) {
                    if (wt.table().equalsIgnoreCase(trg.table())) continue;   // 写自己不算流动
                    if (!seen.add(key(trg.name(), trg.table(), wt.table()))) continue;
                    out.add(new ObjectFlow(trg.table(), wt.table(), label,
                            label + timing + " · " + wt.kind()));
                }
            } catch (RuntimeException ex) {
                // 单个触发器解析失败不影响其余
            }
        }
        return out;
    }

    /**
     * 目录级视图依赖 → 「基表 → 视图」数据流。这是数据库自己维护的依赖目录
     * （PG view_table_usage / Oracle user_dependencies），兜底正则解析不了的嵌套/复杂视图定义。
     * via 与 {@link #viewFlows} 同格式（「视图 x」），两路口径在消费侧按 (src,dst,via) 自然去重。
     */
    public static List<ObjectFlow> catalogViewFlows(DatabaseSchemaInfo schema) {
        List<ObjectFlow> out = new ArrayList<>();
        if (schema == null || schema.dependencies() == null) return out;
        for (var dep : schema.dependencies()) {
            if (!"view".equalsIgnoreCase(dep.objectType())) continue;
            if (dep.objectName() == null || dep.referencedName() == null) continue;
            if (dep.objectName().equalsIgnoreCase(dep.referencedName())) continue;
            out.add(new ObjectFlow(dep.referencedName(), dep.objectName(),
                    "视图 " + dep.objectName(), "数据库依赖目录(视图引用)"));
        }
        return out;
    }

    /** 触发器流的去重键：触发器名 + 源 + 目标（大小写不敏感）。 */
    private static String key(String trigger, String source, String target) {
        return (trigger + "|" + source + "|" + target).toLowerCase();
    }

    /** RoutineInfo.kind → 中文载体名。 */
    private static String routineKindLabel(String kind) {
        return switch (kind == null ? "" : kind.toLowerCase()) {
            case "function" -> "函数";
            case "package body" -> "包体";
            case "event" -> "定时事件";
            case "job" -> "调度作业";
            default -> "存储过程";
        };
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

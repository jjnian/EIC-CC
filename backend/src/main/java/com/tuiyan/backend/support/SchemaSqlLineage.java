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

    /** 存储过程/函数源码 → 「来源表 → 写入目标表」数据流。源码缺失或解析失败的过程静默跳过。 */
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
            for (SqlLineageExtractor.Flow f : r.flows()) {
                out.add(new ObjectFlow(f.source(), f.target(), "存储过程 " + rt.name(),
                        "存储过程 " + rt.name() + " · " + f.kind()));
            }
        }
        return out;
    }
}

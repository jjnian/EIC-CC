package com.tuiyan.backend.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.service.agent.ExplorationAgentService;
import com.tuiyan.backend.service.connector.JdbcConnectorService.DatabaseSchemaInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.ForeignKeyInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.TableInfo;
import com.tuiyan.backend.support.ImplicitRefInferencer;
import com.tuiyan.backend.support.SchemaSqlLineage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把内省得到的 {@link DatabaseSchemaInfo} 里的<b>确定性血缘</b>渲染成一份「结构化图片段」({nodes,edges})：
 * <ul>
 *   <li><b>外键</b>：{@code 子表.列 → 父表.列} 即子表依赖父表(父=主数据/上游) → {@code depends_on} 边；</li>
 *   <li><b>视图定义</b>：FROM/JOIN 的来源表 → 视图 → {@code flows_to} 边；目录级视图依赖
 *       (PG view_table_usage / Oracle user_dependencies)兜底正则解析不了的嵌套/复杂定义，同口径去重；</li>
 *   <li><b>存储过程/函数/定时任务源码</b>：INSERT…SELECT / MERGE / UPDATE…FROM 的来源表 → 写入目标表
 *       → {@code flows_to} 边(经 {@link SchemaSqlLineage} 语句级解析；MySQL 事件/Oracle 调度作业
 *       以 kind=event/job 走同一通路，调度周期写进证据)；</li>
 *   <li><b>触发器体</b>：挂载表 → 触发器写入目标(审计/同步/汇总表) → {@code flows_to} 边
 *       (NEW/OLD 行即来源，挂载表补作上游)；</li>
 *   <li><b>隐式引用（命名约定）</b>：生产库普遍不建外键,{@code xxx_id} 形态列 → 对应表
 *       → {@code depends_on} 推断边(经 {@link ImplicitRefInferencer} 多信号打分：命名 0.4 +
 *       类型匹配/父列唯一/注释提示各 0.1、封顶 0.65，类型冲突剪枝；
 *       证据格式兼容值包含检验,可一键佐证升级)。</li>
 * </ul>
 * 前三者是数据库里现成的血缘 ground truth。与其把 DDL 文本丢给 LLM 让它「重新猜」(可能漏、置信度不确定),
 * 不如据元数据<b>确定性</b>直出为 {@code source=derived、confidence=1.0} 的血缘边,建图时经既有的
 * 「结构化片段直连合并」通路({@code ExperienceOntologyService.extractGraphFragment})免 LLM 重抽直接并入,
 * LLM 只做业务语义增强;隐式引用是唯一的推断项,以低置信候选身份并入,走数据佐证/人工审核闭环。
 * <p>产出格式与探索文档的 {@code EXPLORE_GRAPH} 片段一致:{@code {"nodes":[…],"edges":[…]}};
 * 无任何确定性血缘时返回空串(不产片段,库表实体仍由 LLM 从 DDL 抽取)。
 */
@Component
public class SchemaGraphFragmentRenderer {

    private static final Logger log = LoggerFactory.getLogger(SchemaGraphFragmentRenderer.class);

    private final ObjectMapper om = new ObjectMapper();

    /**
     * 把确定性血缘片段包装成建图侧可识别的隐藏注释块({@code <!-- EXPLORE_GRAPH … -->}),供直接附到
     * DDL 文档末尾;无确定性血缘(空片段)返回空串。建图时 {@code ExperienceOntologyService.extractGraphFragment}
     * 会解析此块并结构化合并、从正文剥离,LLM 不再重抽这层关系。
     */
    public String renderEmbeddedBlock(DatabaseSchemaInfo schema) {
        return renderEmbeddedBlock(schema, null);
    }

    /** 同上，附带数据源名（写进推断边 derived_source，供值包含检验自动定位数据源）。 */
    public String renderEmbeddedBlock(DatabaseSchemaInfo schema, String sourceName) {
        String json = render(schema, sourceName);
        if (json.isBlank()) return "";
        String marker = ExplorationAgentService.GRAPH_MARKER;
        return "\n\n<!-- " + marker + "\n" + json + "\n" + marker + " -->\n";
    }

    /**
     * 渲染确定性血缘片段 JSON(外键 + 视图定义 + 存储过程数据流);无任何血缘返回空串。
     * <p>只为「参与血缘的表/视图」建节点(避免用全部库表淹没图);表节点 label 优先取表注释(业务名),
     * 表名作为别名 —— 让它能与 LLM 抽出的同名业务实体自然折叠。
     */
    public String render(DatabaseSchemaInfo schema) {
        return render(schema, null);
    }

    /** 同上，附带数据源名（可空）。 */
    public String render(DatabaseSchemaInfo schema, String sourceName) {
        if (schema == null || schema.tables() == null || schema.tables().isEmpty()) return "";

        // 表名(标准化) → TableInfo,用于给外键端点解析注释/规范名
        Map<String, TableInfo> byName = new LinkedHashMap<>();
        for (TableInfo t : schema.tables()) {
            byName.putIfAbsent(normKey(t.name()), t);
            byName.putIfAbsent(bareKey(t.name()), t);   // 兼容 schema.table 限定名与裸表名互查
        }

        ObjectMapper m = om;
        ArrayNode nodes = m.createArrayNode();
        ArrayNode edges = m.createArrayNode();
        Map<String, String> nodeIdByTable = new LinkedHashMap<>(); // 标准化表名 → 节点 id
        Set<String> edgeSeen = new HashSet<>();
        int[] seq = {0};

        for (TableInfo t : schema.tables()) {
            if (t.isView()) continue;                 // 视图的血缘看视图定义,不在 FK 层处理
            for (ForeignKeyInfo fk : t.foreignKeys()) {
                if (fk == null || fk.toTable() == null || fk.toTable().isBlank()) continue;
                String childId = nodeFor(t.name(), byName, nodes, nodeIdByTable, seq, m);
                String parentId = nodeFor(fk.toTable(), byName, nodes, nodeIdByTable, seq, m);
                if (childId == null || parentId == null || childId.equals(parentId)) continue;
                String sig = childId + "->" + parentId + "#" + safe(fk.fromColumn()) + "#" + safe(fk.toColumn());
                if (!edgeSeen.add(sig)) continue;     // 同一对列的重复 FK 去重
                ObjectNode e = edges.addObject();
                e.put("id", "sfk_e" + (seq[0]++));
                e.put("from", childId);               // 子表依赖父表:from=子(结果),to=父(来源)
                e.put("to", parentId);
                e.put("rel_type", "depends_on");      // 与 REVERSE_RELS 一致:父=上游,子=下游
                e.put("label", "外键 " + safe(fk.fromColumn()) + " → " + fk.toTable() + "." + safe(fk.toColumn()));
                e.put("source", "derived");
                e.put("confidence", 1.0);
                String cn = fk.constraintName();
                e.put("evidence", "数据库外键约束" + (cn == null || cn.isBlank() ? "" : " " + cn));
            }
        }

        // 视图定义 + 存储过程/定时任务源码 + 触发器体 + 依赖目录里的确定性数据流：
        // 来源表 → 视图/写入目标表。依赖目录与正则解析的视图流 via 同格式，由 edgeSeen 自然去重。
        List<SchemaSqlLineage.ObjectFlow> flows = new ArrayList<>();
        flows.addAll(SchemaSqlLineage.viewFlows(schema));
        flows.addAll(SchemaSqlLineage.catalogViewFlows(schema));
        flows.addAll(SchemaSqlLineage.routineFlows(schema));
        flows.addAll(SchemaSqlLineage.triggerFlows(schema));
        for (SchemaSqlLineage.ObjectFlow f : flows) {
            String srcId = nodeFor(f.source(), byName, nodes, nodeIdByTable, seq, m);
            String dstId = nodeFor(f.target(), byName, nodes, nodeIdByTable, seq, m);
            if (srcId == null || dstId == null || srcId.equals(dstId)) continue;
            String sig = srcId + "->" + dstId + "@" + f.via();
            if (!edgeSeen.add(sig)) continue;         // 同一载体下的重复数据流去重
            ObjectNode e = edges.addObject();
            e.put("id", "sfk_e" + (seq[0]++));
            e.put("from", srcId);                     // 来源表=上游，视图/目标表=下游派生
            e.put("to", dstId);
            e.put("rel_type", "flows_to");
            e.put("label", f.via());
            e.put("source", "derived");
            e.put("confidence", 1.0);
            e.put("evidence", f.evidence());
        }

        // 隐式引用（命名约定推断）：无 FK 声明的 xxx_id 形态引用 → inferred 候选边。
        // 置信度由多信号打分给出（命名 0.4 起步，类型匹配/父列唯一/注释提示各 +0.1、封顶 0.65），
        // 证据按 child.col → parent.col 格式给出，前端「一键数据佐证」可直接解析验证。
        for (ImplicitRefInferencer.ImplicitRef ref : ImplicitRefInferencer.infer(schema)) {
            String childId = nodeFor(ref.childTable(), byName, nodes, nodeIdByTable, seq, m);
            String parentId = nodeFor(ref.parentTable(), byName, nodes, nodeIdByTable, seq, m);
            if (childId == null || parentId == null || childId.equals(parentId)) continue;
            String sig = childId + "->" + parentId + "#nr#" + ref.childColumn().toLowerCase(Locale.ROOT);
            if (!edgeSeen.add(sig)) continue;
            String childBare = ImplicitRefInferencer.bareName(ref.childTable());
            String parentBare = ImplicitRefInferencer.bareName(ref.parentTable());
            ObjectNode e = edges.addObject();
            e.put("id", "sfk_e" + (seq[0]++));
            e.put("from", childId);               // 子表依赖父表：与 FK 通路同向
            e.put("to", parentId);
            e.put("rel_type", "depends_on");
            e.put("label", "命名推断 " + ref.childColumn() + " → " + parentBare + "." + ref.parentColumn());
            e.put("source", "inferred");
            e.put("confidence", ref.confidence());
            e.put("evidence", "命名约定(无外键声明) " + childBare + "." + ref.childColumn()
                    + " → " + parentBare + "." + ref.parentColumn()
                    + "（信号：" + ref.signals() + "），建议值包含检验");
            if (sourceName != null && !sourceName.isBlank()) e.put("derived_source", sourceName);
            ArrayNode dt = e.putArray("derived_tables");
            dt.add(childBare);
            dt.add(parentBare);
        }

        if (edges.isEmpty()) return "";               // 没有可直出的血缘,不产片段
        ObjectNode root = m.createObjectNode();
        root.set("nodes", nodes);
        root.set("edges", edges);
        try {
            return om.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[schema-fragment] 序列化确定性血缘片段失败(忽略): {}", e.toString());
            return "";
        }
    }

    /** 取(或按需建)某表的节点,返回节点 id;无法解析返回 null。 */
    private String nodeFor(String tableRef, Map<String, TableInfo> byName, ArrayNode nodes,
                           Map<String, String> nodeIdByTable, int[] seq, ObjectMapper m) {
        if (tableRef == null || tableRef.isBlank()) return null;
        String key = normKey(tableRef);
        String existing = nodeIdByTable.get(key);
        if (existing != null) return existing;
        TableInfo t = byName.get(key);
        if (t == null) t = byName.get(bareKey(tableRef));
        String name = t != null ? t.name() : tableRef;
        String comment = t != null && t.comment() != null ? t.comment().trim() : "";
        String label = comment.isBlank() ? name : comment;

        String id = "sfk_n" + (seq[0]++);
        nodeIdByTable.put(key, id);
        ObjectNode n = nodes.addObject();
        n.put("id", id);
        n.put("label", label);
        n.put("type", "entity");
        n.put("source", "derived");
        n.put("evidence", "库表 " + name);
        // 业务名做 label 时,表名作别名,便于与 LLM 抽出的同名实体折叠
        if (!label.equalsIgnoreCase(name)) {
            ArrayNode aliases = n.putArray("aliases");
            aliases.add(name);
        }
        return id;
    }

    private static String normKey(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /** 裸表名(去掉 schema.前缀),兼容 pgsql 的 schema.table 限定名。 */
    private static String bareKey(String s) {
        String k = normKey(s);
        int dot = k.lastIndexOf('.');
        return dot >= 0 ? k.substring(dot + 1) : k;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

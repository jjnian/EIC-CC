package com.tuiyan.backend.service;

import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.service.connector.JdbcConnectorService.ColumnInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.DatabaseSchemaInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.ForeignKeyInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.TableInfo;
import com.tuiyan.backend.service.indexing.GraphNodeIndexService;
import com.tuiyan.backend.support.ImplicitRefInferencer;
import com.tuiyan.backend.support.SchemaSqlLineage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 确定性结构建图：直接从数据库内省结果构建业务血缘图，<b>完全绕过 LLM</b>。
 * <p>面向「千张 / 万张表」规模——LLM 路线受 128k 上下文与单篇 40k 字符截断限制，物理上装不下大库；
 * 而表、列、外键本身就是引用血缘的 ground truth，可确定性直出：
 * <ul>
 *   <li><b>每张表（含视图）</b> → 一个 {@code entity} 节点，表注释做业务名、列做属性、schema 做领域(domain)；</li>
 *   <li><b>每个外键</b> → 一条 {@code depends_on} 血缘边（父表=上游，{@code confidence=1.0}）；</li>
 *   <li><b>视图定义 / 存储过程源码</b> → {@code flows_to} 数据流边（来源表 → 视图 / 写入目标表，
 *       经 {@link SchemaSqlLineage} 语句级确定性解析）；</li>
 *   <li><b>隐式引用（命名约定）</b> → {@code depends_on} 推断边（{@code inferred/0.5}，经
 *       {@link ImplicitRefInferencer}）——生产库普遍不建外键，命名约定是引用血缘的最后线索，
 *       证据按 child.col → parent.col 格式给出，可被值包含检验一键佐证、进人工审核队列。</li>
 * </ul>
 * 产物是一张<b>完整且正确</b>的结构血缘图（任意表数、零 token），直接落成新本体模型。
 * LLM 语义增强（业务命名、跨系统推断血缘）可事后按域增量叠加，不阻塞结构建图。
 */
@Service
public class StructuralGraphService {

    private static final Logger log = LoggerFactory.getLogger(StructuralGraphService.class);

    /** 单表最多落多少列为属性：极宽表（数百列）截断，防单节点属性 JSON 过大。 */
    private static final int MAX_ATTRS_PER_TABLE = 200;
    /** 网格布局占位间距（精细布局归前端渲染层，这里仅给确定性坐标避免全堆在原点）。 */
    private static final int GRID_SPACING = 220;

    private final DataSourceService dataSourceService;
    private final OntologyModelService modelService;
    private final GraphNodeIndexService nodeIndex;

    public StructuralGraphService(DataSourceService dataSourceService, OntologyModelService modelService,
                                  GraphNodeIndexService nodeIndex) {
        this.dataSourceService = dataSourceService;
        this.modelService = modelService;
        this.nodeIndex = nodeIndex;
    }

    /** 结构建图结果摘要。 */
    public record BuildResult(String modelId, String title, String database,
                              int tableCount, int viewCount, int columnCount,
                              int fkCount, int nodeCount, int edgeCount) {}

    /**
     * 从某关系型数据源全库内省，确定性构建结构血缘图并落成一个新本体模型。
     * @param dataSourceId 数据源 id（须为关系库，含归属校验）
     * @param title        模型标题（空则用「『数据源名』结构血缘图」）
     */
    /** 无进度回调的结构建图（同步端点用）。 */
    public BuildResult buildFromDataSource(String dataSourceId, String title) {
        return buildFromDataSource(dataSourceId, title, (k, l) -> {});
    }

    /**
     * 结构建图（带进度回调，供 SSE 流式端点用，避免大库同步请求 HTTP 超时）。
     * @param step 进度回调 (key, label)
     */
    public BuildResult buildFromDataSource(String dataSourceId, String title,
                                           BiConsumer<String, String> step) {
        step.accept("introspect", "正在内省全库结构（大库较慢，请耐心等待）…");
        String sourceName = dataSourceService.sourceName(dataSourceId);
        DatabaseSchemaInfo schema = dataSourceService.introspectSchemaInfoFull(dataSourceId);
        BuiltGraph built = buildGraph(schema, sourceName);
        step.accept("saving", "已内省 " + (built.tableCount() + built.viewCount()) + " 表/视图 · "
                + built.fkCount() + " 外键，正在落库 " + built.graph().getNodes().size()
                + " 节点 / " + built.graph().getEdges().size() + " 边（大库落库较慢）…");

        String modelTitle = (title == null || title.isBlank())
                ? "「" + sourceName + "」结构血缘图" : title.trim();
        OntologyModel m = new OntologyModel();
        m.setTitle(modelTitle);
        m.setDesc("由数据源「" + sourceName + "」全库结构确定性生成（"
                + built.graph().getNodes().size() + " 表/视图 · " + built.graph().getEdges().size() + " 外键血缘）");
        m.setGraphData(built.graph());
        OntologyModel saved = modelService.save(m);
        nodeIndex.reindex(saved.getId(), built.graph().getNodes());   // 异步建节点向量索引，供对话改图语义检索

        log.info("[structural-graph] 建图完成 model={} 表={} 视图={} 列={} 外键={} 节点={} 边={}",
                saved.getId(), built.tableCount(), built.viewCount(), built.columnCount(), built.fkCount(),
                built.graph().getNodes().size(), built.graph().getEdges().size());
        return new BuildResult(saved.getId(), modelTitle, built.database(),
                built.tableCount(), built.viewCount(), built.columnCount(), built.fkCount(),
                built.graph().getNodes().size(), built.graph().getEdges().size());
    }

    /** 纯转换的中间产物：图 + 统计（抽成静态方法便于离线单测，不碰 DB/Spring）。 */
    record BuiltGraph(OntologyModel.GraphData graph, String database,
                      int tableCount, int viewCount, int columnCount, int fkCount) {}

    /**
     * 纯函数：把内省 schema 转成结构血缘图（表→entity 节点、列→属性、外键→depends_on 边）。
     * 无 DB / 无 Spring 依赖，可离线测试。
     */
    static BuiltGraph buildGraph(DatabaseSchemaInfo schema, String sourceName) {
        List<TableInfo> tables = schema == null || schema.tables() == null ? List.of() : schema.tables();
        if (tables.isEmpty()) {
            throw new IllegalStateException("该数据源内省不到任何表，无法结构建图");
        }
        String database = schema.database() == null ? "" : schema.database();

        // 表名（标准化：全名 + 裸名）→ 节点 id，供外键端点解析
        Map<String, String> nodeIdByTable = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>(tables.size());
        int cols = (int) Math.ceil(Math.sqrt(Math.max(1, tables.size())));
        int viewCount = 0, columnCount = 0, idx = 0;

        for (TableInfo t : tables) {
            String nodeId = "t" + idx;
            String full = norm(t.name());
            nodeIdByTable.putIfAbsent(full, nodeId);
            nodeIdByTable.putIfAbsent(bare(full), nodeId);

            boolean isView = t.isView();
            if (isView) viewCount++;
            String comment = t.comment() == null ? "" : t.comment().trim();
            String label = comment.isBlank() ? t.name() : comment;

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", nodeId);
            node.put("label", label);
            node.put("type", "entity");
            node.put("source", "derived");
            node.put("confidence", 1.0);
            node.put("domain", domainOf(t.name(), database));
            node.put("derived_source", sourceName);
            if (!database.isBlank()) node.put("derived_database", database);
            node.put("evidence", "库表 " + t.name() + (isView ? "（视图）" : ""));
            node.put("x", (double) ((idx % cols) * GRID_SPACING));
            node.put("y", (double) ((idx / cols) * GRID_SPACING));
            // 业务名做 label 时，表名作别名，便于后续与 LLM 抽出的同名业务实体折叠
            if (!label.equalsIgnoreCase(t.name())) {
                node.put("aliases", List.of(t.name()));
            }
            // 列 → 属性（数据字典），极宽表截断
            List<ColumnInfo> tcols = t.columns() == null ? List.of() : t.columns();
            List<Map<String, Object>> attrs = new ArrayList<>(Math.min(tcols.size(), MAX_ATTRS_PER_TABLE));
            for (ColumnInfo c : tcols) {
                if (attrs.size() >= MAX_ATTRS_PER_TABLE) break;
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("name", c.name());
                a.put("valueSpace", c.dataType() == null ? "" : c.dataType());
                a.put("column", c.name());
                a.put("source", "derived");
                a.put("sourceMethod", "db");
                if (c.primaryKey()) a.put("note", "主键");
                attrs.add(a);
                columnCount++;
            }
            if (!attrs.isEmpty()) node.put("attributes", attrs);
            nodes.add(node);
            idx++;
        }

        // 外键 → depends_on 血缘边（子表依赖父表：from=子/结果，to=父/来源，与 REVERSE_RELS 一致）
        List<Map<String, Object>> edges = new ArrayList<>();
        int fkCount = 0, ei = 0, dangling = 0;
        for (TableInfo t : tables) {
            if (t.isView()) continue;
            String childId = nodeIdByTable.get(norm(t.name()));
            if (childId == null) continue;
            for (ForeignKeyInfo fk : t.foreignKeys()) {
                if (fk == null || fk.toTable() == null || fk.toTable().isBlank()) continue;
                fkCount++;
                String parentId = nodeIdByTable.get(norm(fk.toTable()));
                if (parentId == null) parentId = nodeIdByTable.get(bare(norm(fk.toTable())));
                if (parentId == null || parentId.equals(childId)) { dangling++; continue; }
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("id", "fk" + (ei++));
                edge.put("from", childId);
                edge.put("to", parentId);
                edge.put("rel_type", "depends_on");
                edge.put("source", "derived");
                edge.put("confidence", 1.0);
                edge.put("label", "外键 " + safe(fk.fromColumn()) + " → " + fk.toTable() + "." + safe(fk.toColumn()));
                edge.put("evidence", "数据库外键约束"
                        + (fk.constraintName() == null || fk.constraintName().isBlank() ? "" : " " + fk.constraintName()));
                edge.put("domain", domainOf(t.name(), database));
                edges.add(edge);
            }
        }
        if (dangling > 0) {
            log.info("[structural-graph] {} 条外键的父表不在内省范围内，已跳过", dangling);
        }

        // SQL 定义体血缘 → flows_to 边：视图定义、存储过程/定时任务源码、触发器体是血缘 ground truth，
        // 经 SchemaSqlLineage 确定性解析「来源表 → 视图 / 写入目标表」的表级数据流（不过 LLM，confidence=1.0）。
        // 目录级视图依赖（catalogViewFlows）与正则解析 via 同格式，由 flowSeen 自然去重。
        int viewLineage = 0, routineLineage = 0;
        Set<String> flowSeen = new HashSet<>();
        List<SchemaSqlLineage.ObjectFlow> defFlows = new ArrayList<>();
        defFlows.addAll(SchemaSqlLineage.viewFlows(schema));
        defFlows.addAll(SchemaSqlLineage.catalogViewFlows(schema));
        defFlows.addAll(SchemaSqlLineage.routineFlows(schema));
        defFlows.addAll(SchemaSqlLineage.triggerFlows(schema));
        for (SchemaSqlLineage.ObjectFlow f : defFlows) {
            boolean isView = f.via().startsWith("视图");
            String srcId = resolveNode(nodeIdByTable, f.source());
            String dstId = resolveNode(nodeIdByTable, f.target());
            if (srcId == null || dstId == null || srcId.equals(dstId)) continue;   // 端点不在库内 / 自引用，跳过
            if (!flowSeen.add(srcId + "->" + dstId + "@" + f.via())) continue;     // 同一载体重复数据流去重
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", (isView ? "vw" : "rt") + (ei++));
            edge.put("from", srcId);        // 来源表 → 视图/目标表（flows_to 正向：来源=上游，产物=下游）
            edge.put("to", dstId);
            edge.put("rel_type", "flows_to");
            edge.put("source", "derived");
            edge.put("confidence", 1.0);
            edge.put("label", f.via());
            edge.put("evidence", f.evidence());
            edge.put("domain", domainOf(f.target(), database));
            edges.add(edge);
            if (isView) viewLineage++; else routineLineage++;
        }
        if (viewLineage > 0) {
            log.info("[structural-graph] 从视图定义确定性解析出 {} 条视图血缘边", viewLineage);
        }
        if (routineLineage > 0) {
            log.info("[structural-graph] 从存储过程/函数源码确定性解析出 {} 条数据流血缘边", routineLineage);
        }

        // 隐式引用血缘 → depends_on 推断边：生产库普遍不建外键，命名约定是引用关系的最后线索。
        // inferred 候选直出（不混入确定性血缘），置信度由多信号打分（命名 0.4 + 类型匹配/父列唯一/
        // 注释提示各 0.1、封顶 0.65），证据按 child.col → parent.col 格式，
        // 可被「值包含检验」一键佐证升级为数据证实，也自动进入人工审核队列。
        int implicitRefs = 0;
        for (ImplicitRefInferencer.ImplicitRef ref : ImplicitRefInferencer.infer(schema)) {
            String childId = resolveNode(nodeIdByTable, ref.childTable());
            String parentId = resolveNode(nodeIdByTable, ref.parentTable());
            if (childId == null || parentId == null || childId.equals(parentId)) continue;
            String childBare = ImplicitRefInferencer.bareName(ref.childTable());
            String parentBare = ImplicitRefInferencer.bareName(ref.parentTable());
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", "nr" + (ei++));
            edge.put("from", childId);          // 子表依赖父表：与 FK 通路同向（父=上游）
            edge.put("to", parentId);
            edge.put("rel_type", "depends_on");
            edge.put("source", "inferred");
            edge.put("confidence", ref.confidence());
            edge.put("label", "命名推断 " + ref.childColumn() + " → " + parentBare + "." + ref.parentColumn());
            edge.put("evidence", "命名约定(无外键声明) " + childBare + "." + ref.childColumn()
                    + " → " + parentBare + "." + ref.parentColumn()
                    + "（信号：" + ref.signals() + "），建议值包含检验");
            edge.put("derived_source", sourceName);
            edge.put("derived_tables", List.of(childBare, parentBare));
            edge.put("domain", domainOf(ref.childTable(), database));
            edges.add(edge);
            implicitRefs++;
        }
        if (implicitRefs > 0) {
            log.info("[structural-graph] 按命名约定推断出 {} 条隐式引用血缘边（inferred，多信号打分，待佐证）", implicitRefs);
        }

        OntologyModel.GraphData g = new OntologyModel.GraphData();
        g.setNodes(nodes);
        g.setEdges(edges);
        return new BuiltGraph(g, database, tables.size() - viewCount, viewCount, columnCount, fkCount);
    }

    /** 领域(domain)：pgsql 的 schema.table 取 schema 前缀；否则用库名。 */
    private static String domainOf(String tableName, String database) {
        if (tableName != null) {
            int dot = tableName.lastIndexOf('.');
            if (dot > 0) return tableName.substring(0, dot);
        }
        return database == null || database.isBlank() ? "未分域" : database;
    }

    /** 按「全名 → 裸名」两级解析表引用到节点 id；都不在库内返回 null。 */
    private static String resolveNode(Map<String, String> nodeIdByTable, String tableRef) {
        String id = nodeIdByTable.get(norm(tableRef));
        return id != null ? id : nodeIdByTable.get(bare(norm(tableRef)));
    }

    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
    private static String bare(String normKey) {
        int dot = normKey.lastIndexOf('.');
        return dot >= 0 ? normKey.substring(dot + 1) : normKey;
    }
    private static String safe(String s) { return s == null ? "" : s; }
}

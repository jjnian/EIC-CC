package com.tuiyan.backend.service.llm;

import com.tuiyan.backend.service.connector.JdbcConnectorService.ColumnInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.DatabaseSchemaInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.ForeignKeyInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.TableInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.UniqueKeyInfo;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据库 schema 序列化（给 chat / extract 两种场景用）+ schema → 本体血缘图的专用 prompt 构造。
 */
@Component
public class SchemaPromptRenderer {

    // chat 上下文里嵌入 schema 时的预算：列详情每张表只挑前 N 列，避免炸 context
    private static final int CHAT_SCHEMA_COLS_PER_TABLE = 12;
    private static final int CHAT_SCHEMA_TABLES_MAX = 60;
    // 视图定义体进 prompt 的字符上限：长视图截断，避免炸 context
    private static final int VIEW_DEF_MAX_CHARS = 1500;

    /**
     * Compact 版 schema 渲染：chat 场景用，每张表只列重要列（PK/FK/unique + 前几列），
     * 外键单独成段。用 ASCII tree 让 LLM 容易解析。
     */
    public String renderSchemaCompact(DatabaseSchemaInfo s) {
        StringBuilder sb = new StringBuilder();
        List<TableInfo> tables = s.tables();
        int total = tables.size();
        int shown = Math.min(total, CHAT_SCHEMA_TABLES_MAX);
        // 1) 表 + 简化列清单
        for (int i = 0; i < shown; i++) {
            TableInfo t = tables.get(i);
            sb.append("  · ").append(t.name());
            if (t.comment() != null && !t.comment().isBlank()) {
                sb.append(" (").append(t.comment()).append(")");
            }
            if (t.estimatedRows() != null && t.estimatedRows() > 0) {
                sb.append(" ~").append(t.estimatedRows()).append("行");
            }
            sb.append("\n");
            List<ColumnInfo> picked = pickImportantColumns(t, CHAT_SCHEMA_COLS_PER_TABLE);
            for (ColumnInfo c : picked) {
                sb.append("      - ").append(c.name())
                  .append(" : ").append(c.dataType());
                if (c.primaryKey()) sb.append(" [PK]");
                if (!c.nullable()) sb.append(" NOT NULL");
                if (c.comment() != null && !c.comment().isBlank()) {
                    sb.append(" // ").append(c.comment());
                }
                sb.append("\n");
            }
            int omitted = t.columns().size() - picked.size();
            if (omitted > 0) {
                sb.append("      … 省略 ").append(omitted).append(" 个非关键列\n");
            }
        }
        if (total > shown) {
            sb.append("  · …（共 ").append(total).append(" 张表，已截 ").append(shown).append("）\n");
        }
        // 2) 外键单独列出（最直接的血缘线索）
        List<String> fkLines = new ArrayList<>();
        for (TableInfo t : tables) {
            for (ForeignKeyInfo fk : t.foreignKeys()) {
                fkLines.add(t.name() + "." + fk.fromColumn()
                        + " → " + fk.toTable() + "." + fk.toColumn());
            }
        }
        if (!fkLines.isEmpty()) {
            sb.append("  外键 (=显式血缘):\n");
            int max = Math.min(fkLines.size(), 60);
            for (int i = 0; i < max; i++) sb.append("      ").append(fkLines.get(i)).append("\n");
            if (fkLines.size() > max) {
                sb.append("      … 还有 ").append(fkLines.size() - max).append(" 条外键\n");
            }
        }
        return sb.toString();
    }

    /** 重要列选择策略：PK/FK/unique + 业务名 (status/type/name/code/no/amount/qty/_at) 优先，剩下按 ordinal 顺序补齐。 */
    private List<ColumnInfo> pickImportantColumns(TableInfo t, int limit) {
        if (t.columns().size() <= limit) return t.columns();
        Set<String> fkCols = new HashSet<>();
        for (ForeignKeyInfo fk : t.foreignKeys()) fkCols.add(fk.fromColumn());
        Set<String> uniqCols = new HashSet<>();
        for (UniqueKeyInfo uk : t.uniqueKeys()) uniqCols.addAll(uk.columns());

        List<ColumnInfo> picked = new ArrayList<>();
        List<ColumnInfo> rest = new ArrayList<>();
        for (ColumnInfo c : t.columns()) {
            boolean important = c.primaryKey() || fkCols.contains(c.name())
                    || uniqCols.contains(c.name()) || isBusinessName(c.name());
            if (important) picked.add(c);
            else rest.add(c);
        }
        for (ColumnInfo c : rest) {
            if (picked.size() >= limit) break;
            picked.add(c);
        }
        picked.sort((a, b) -> Integer.compare(a.ordinalPosition(), b.ordinalPosition()));
        return picked.size() > limit ? picked.subList(0, limit) : picked;
    }

    private static boolean isBusinessName(String col) {
        if (col == null) return false;
        String c = col.toLowerCase();
        return c.equals("name") || c.equals("code") || c.equals("no") || c.equals("title")
                || c.startsWith("status") || c.startsWith("type") || c.startsWith("kind")
                || c.contains("amount") || c.contains("price") || c.contains("qty")
                || c.contains("quantity") || c.endsWith("_at") || c.endsWith("_time")
                || c.endsWith("_date");
    }

    /**
     * 构造"DB schema → ontology lineage"的完整 prompt。
     * <p>系统 prompt 用 {@link LlmPrompts#SCHEMA_TO_ONTOLOGY_SYSTEM}，user 部分把 schema 全量
     * 详尽展开（不像 chat 场景那样省略列），让 LLM 拿到最完整的"事实"。
     */
    public GraphPromptBuilder.SchemaExtractPrompt buildSchemaExtractPrompt(DatabaseSchemaInfo schema,
                                                                           String sourceName,
                                                                           String extraHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是从数据源「").append(sourceName).append("」 (")
          .append(schema.kind()).append(", 库: ").append(schema.database())
          .append(") 内省得到的完整 schema。请严格按 system 中的映射规则，把它转换成本体血缘图。\n\n");
        sb.append("====== SCHEMA START ======\n");
        sb.append(renderSchemaFull(schema));
        sb.append("====== SCHEMA END ======\n\n");
        if (extraHint != null && !extraHint.isBlank()) {
            sb.append("【用户额外提示】").append(extraHint).append("\n\n");
        }
        sb.append("现在输出 JSON。必须满足：\n");
        sb.append("  - 允许按业务概念合并多张表，不要机械做一表一节点；\n");
        sb.append("  - 外键和列只是证据，只有能表达真实语义时才输出节点/边；\n");
        sb.append("  - attributes 只能列出 SCHEMA 中真实出现的列，且用 `column` 保留物理列追溯；\n");
        sb.append("  - constraints 只能基于真实 PK / 唯一键 / NOT NULL / 声明的 FK；\n");
        sb.append("  - VIEW 的 DEFINITION 是血缘 ground truth：据其 FROM/JOIN 抽取「视图→来源表」的派生边，据 SELECT 聚合/表达式抽取指标口径；这类边即使无显式 FK 也应输出（服务端会按视图定义保留）；\n");
        sb.append("  - 节点和边的 `derived_tables` 需要明确写出来源表；\n");
        sb.append("  - 节点 id 要稳定、边 id 要幂等；\n");
        sb.append("  - 不要输出 `question` 字段；\n");
        sb.append("  - 不允许出现 add_nodes 之外的 from/to 引用（无悬空边）；\n");
        sb.append("  - ⚠ 服务端会做事实校验：编造的表/列/FK 会被自动删除。宁可少写也不要多写。\n");
        return new GraphPromptBuilder.SchemaExtractPrompt(LlmPrompts.SCHEMA_TO_ONTOLOGY_SYSTEM, sb.toString());
    }

    /** 两阶段抽取共享的前言：来源说明 + 完整 schema + 用户提示。 */
    private void appendSchemaPreamble(StringBuilder sb, DatabaseSchemaInfo schema, String sourceName, String extraHint) {
        sb.append("以下是从数据源「").append(sourceName).append("」 (")
          .append(schema.kind()).append(", 库: ").append(schema.database())
          .append(") 内省得到的完整 schema。请严格按 system 中的映射规则处理。\n\n");
        sb.append("====== SCHEMA START ======\n");
        sb.append(renderSchemaFull(schema));
        sb.append("====== SCHEMA END ======\n\n");
        if (extraHint != null && !extraHint.isBlank()) {
            sb.append("【用户额外提示】").append(extraHint).append("\n\n");
        }
    }

    /**
     * 任务分解·阶段1（仅节点）：只抽实体 + 属性 + 约束，不抽边。
     * <p>聚焦"识别业务概念"这一件事，节点 id 用确定式 {@code t_<table>}，供阶段2 引用。
     */
    public GraphPromptBuilder.SchemaExtractPrompt buildNodeStagePrompt(DatabaseSchemaInfo schema,
                                                                       String sourceName,
                                                                       String extraHint) {
        StringBuilder sb = new StringBuilder();
        appendSchemaPreamble(sb, schema, sourceName, extraHint);
        sb.append("【本轮任务 = 仅抽节点：实体 + 属性 + 约束】\n");
        sb.append("  - 按 Rule 1/2 识别业务概念节点（可把紧密相关的多张表合并为一个概念）；\n");
        sb.append("  - 按 Rule 5 填 attributes（只用 SCHEMA 中真实出现的列，并用 `column` 保留物理列追溯）；\n");
        sb.append("  - 按 Rule 6 填 constraints（PK / 唯一键 / NOT NULL 簇）；\n");
        sb.append("  - 节点 id 用确定式 t_<sanitized_table_name>，保证同样 schema 同样 id；\n");
        sb.append("  - ⚠ 本轮只输出 add_nodes；add_edges 必须为空数组 []（关系与血缘下一轮再做）；\n");
        sb.append("  - 服务端会做事实校验：编造的表/列会被删除，宁可少写也不要多写。\n");
        sb.append("输出 JSON：{\"add_nodes\":[...], \"add_edges\":[]}\n");
        return new GraphPromptBuilder.SchemaExtractPrompt(LlmPrompts.SCHEMA_TO_ONTOLOGY_SYSTEM, sb.toString());
    }

    /**
     * 任务分解·阶段2（仅边）：在阶段1 已固定的节点集上，只抽关系 + 血缘。
     * <p>把已知节点（id + label + 来源表）回灌给模型，让它专注连边、且 from/to 只能引用这些 id。
     */
    public GraphPromptBuilder.SchemaExtractPrompt buildEdgeStagePrompt(DatabaseSchemaInfo schema,
                                                                       String sourceName,
                                                                       String extraHint,
                                                                       JsonNode knownNodes) {
        StringBuilder sb = new StringBuilder();
        appendSchemaPreamble(sb, schema, sourceName, extraHint);
        sb.append("【上一轮已确定的节点（id 固定，不可新增 / 改名）】\n");
        if (knownNodes != null && knownNodes.isArray()) {
            for (JsonNode n : knownNodes) {
                String id = n.path("id").asText("");
                if (id.isEmpty()) continue;
                sb.append("  - ").append(id).append(" : ").append(n.path("label").asText(""));
                JsonNode dt = n.path("derived_tables");
                if (dt.isArray() && !dt.isEmpty()) {
                    List<String> names = new ArrayList<>();
                    for (JsonNode one : dt) names.add(one.asText(""));
                    sb.append("（来源表: ").append(String.join(", ", names)).append("）");
                }
                sb.append("\n");
            }
        }
        sb.append("\n【本轮任务 = 仅抽边：关系 + 血缘，节点已固定】\n");
        sb.append("  - 按 Rule 3（FK→语义边）、Rule 4（命名推断，严格）抽关系；\n");
        sb.append("  - 按 VIEW 的 DEFINITION 中 FROM/JOIN 抽「视图→来源表」派生血缘、SELECT 聚合抽指标口径（这类边即使无 FK 也应输出）；\n");
        sb.append("  - 每条边的 from/to 必须是上面列表里的节点 id；不要引用不存在的 id，不要新增 / 修改节点；\n");
        sb.append("  - 边 id 用确定式 e_fk_<child>_<col>__<parent>；\n");
        sb.append("  - ⚠ 本轮只输出 add_edges；add_nodes 必须为空数组 []；\n");
        sb.append("  - 没有 FK / 视图依据就不要硬造边（H8：少而准 > 多而错）。\n");
        sb.append("输出 JSON：{\"add_nodes\":[], \"add_edges\":[...]}\n");
        return new GraphPromptBuilder.SchemaExtractPrompt(LlmPrompts.SCHEMA_TO_ONTOLOGY_SYSTEM, sb.toString());
    }

    /** Full 版 schema 渲染：extract 场景用，每张表完整列出所有列 + 全部约束 + 全部外键。 */
    public String renderSchemaFull(DatabaseSchemaInfo s) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据库类型: ").append(s.kind()).append("\n");
        sb.append("数据库名:   ").append(s.database()).append("\n");
        sb.append("表数量:     ").append(s.tables().size()).append("\n\n");
        for (TableInfo t : s.tables()) {
            sb.append(t.isView() ? "VIEW " : "TABLE ").append(t.name());
            if (t.comment() != null && !t.comment().isBlank()) {
                sb.append("    -- ").append(t.comment());
            }
            if (!t.isView() && t.estimatedRows() != null && t.estimatedRows() > 0) {
                sb.append("  (~").append(t.estimatedRows()).append(" rows)");
            }
            sb.append("\n");
            // 列
            for (ColumnInfo c : t.columns()) {
                sb.append("  COL ");
                if (c.primaryKey()) sb.append("[PK] ");
                sb.append(c.name())
                  .append("  ").append(c.dataType());
                if (!c.nullable()) sb.append("  NOT NULL");
                if (c.defaultValue() != null && !c.defaultValue().isBlank()) {
                    sb.append("  DEFAULT ").append(c.defaultValue());
                }
                if (c.comment() != null && !c.comment().isBlank()) {
                    sb.append("    -- ").append(c.comment());
                }
                sb.append("\n");
            }
            // 外键
            for (ForeignKeyInfo fk : t.foreignKeys()) {
                sb.append("  FK  ").append(fk.fromColumn())
                  .append(" -> ").append(fk.toTable()).append("(").append(fk.toColumn()).append(")")
                  .append("    [constraint=").append(fk.constraintName()).append("]\n");
            }
            // 唯一约束
            for (UniqueKeyInfo uk : t.uniqueKeys()) {
                sb.append("  UNQ ").append(uk.name())
                  .append(" (").append(String.join(", ", uk.columns())).append(")\n");
            }
            // 视图定义体 = 血缘 ground truth（FROM/JOIN → 派生边，SELECT 聚合 → 指标口径）
            if (t.isView() && t.definition() != null && !t.definition().isBlank()) {
                String def = t.definition().strip();
                if (def.length() > VIEW_DEF_MAX_CHARS) {
                    def = def.substring(0, VIEW_DEF_MAX_CHARS) + "\n…[定义体截断]";
                }
                sb.append("  DEFINITION (该视图的 SQL，FROM/JOIN=血缘来源，SELECT 聚合=口径):\n");
                for (String line : def.split("\n")) {
                    sb.append("    ").append(line).append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}

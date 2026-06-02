package com.tuiyan.backend.service.llm;

import com.tuiyan.backend.model.Constraint;
import com.tuiyan.backend.model.MentionRef;
import com.tuiyan.backend.model.PredictRequest;
import com.tuiyan.backend.service.connector.JdbcConnectorService.DatabaseSchemaInfo;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 图谱 → LLM Prompt 的瘦门面：本身不持有拼接逻辑，只把请求委派给职责单一的协作组件。
 * <p>保留对外的全部 public 方法签名与公共嵌套 record，外部依赖（ChatLlmService /
 * PredictLlmService / SchemaOntologyService）无需改动。实际逻辑分散在：
 * <ul>
 *   <li>{@link ChatPromptBuilder} — chat user prompt 拼接；</li>
 *   <li>{@link PredictPromptBuilder} — 推演 prompt 拼接；</li>
 *   <li>{@link SchemaPromptRenderer} — DB schema 序列化 + extract prompt；</li>
 *   <li>{@link GraphSummarizer} — 图摘要 / 上下文截断（被上面几个共享）。</li>
 * </ul>
 */
@Component
public class GraphPromptBuilder {

    private final ChatPromptBuilder chatPromptBuilder;
    private final PredictPromptBuilder predictPromptBuilder;
    private final SchemaPromptRenderer schemaPromptRenderer;
    private final GraphSummarizer graphSummarizer;

    public GraphPromptBuilder(ChatPromptBuilder chatPromptBuilder,
                              PredictPromptBuilder predictPromptBuilder,
                              SchemaPromptRenderer schemaPromptRenderer,
                              GraphSummarizer graphSummarizer) {
        this.chatPromptBuilder = chatPromptBuilder;
        this.predictPromptBuilder = predictPromptBuilder;
        this.schemaPromptRenderer = schemaPromptRenderer;
        this.graphSummarizer = graphSummarizer;
    }

    /** 截断结果：保留下来的节点 / 边 + 被丢弃的数量。 */
    public record TruncatedGraph(List<Map<String, Object>> nodes,
                                 List<Map<String, Object>> edges,
                                 int droppedNodes,
                                 int droppedEdges) {}

    /** 推演 prompt 的结构化产物，供 orchestrator 写入 Scenario.rawPrompt 与 LLM 调用复用。 */
    public record PredictPromptArtifact(String system, String user,
                                        boolean truncated, int droppedNodes, int droppedEdges) {}

    /** RAG 检索结果片段 */
    public record RagChunk(String content, String sourceName, double score) {}

    /**
     * 数据库 schema 概览片段，用于让 LLM 知道当前工作空间有哪些表可参考。
     * <p>从 only-table-names 升级为可承载结构化详情（detail 非 null 时使用结构化序列化）。
     */
    public record DbSchema(String sourceName,
                           String kind,
                           String database,
                           List<String> tables,
                           DatabaseSchemaInfo detail) {
        // 兼容旧调用：只有表名时
        public DbSchema(String sourceName, String kind, String database, List<String> tables) {
            this(sourceName, kind, database, tables, null);
        }
    }

    /** schema → ontology user prompt 产物。 */
    public record SchemaExtractPrompt(String system, String user) {}

    // ============================================================
    // chat prompt（委派 ChatPromptBuilder）
    // ============================================================

    /** chat 用 user prompt：把现有图谱摘要放在前面，作为已知上下文。 */
    public String buildChatPrompt(List<Map<String, Object>> nodes,
                                  List<Map<String, Object>> edges,
                                  String message) {
        return buildChatPrompt(nodes, edges, message, null, null, null);
    }

    /** chat 用 user prompt（含 RAG 数据源上下文）。 */
    public String buildChatPrompt(List<Map<String, Object>> nodes,
                                  List<Map<String, Object>> edges,
                                  String message,
                                  List<RagChunk> ragChunks) {
        return buildChatPrompt(nodes, edges, message, ragChunks, null, null);
    }

    /** chat 用 user prompt（含 RAG + 数据库 schema 上下文）。 */
    public String buildChatPrompt(List<Map<String, Object>> nodes,
                                  List<Map<String, Object>> edges,
                                  String message,
                                  List<RagChunk> ragChunks,
                                  List<DbSchema> dbSchemas) {
        return buildChatPrompt(nodes, edges, message, ragChunks, dbSchemas, null);
    }

    /** chat 用 user prompt（含 RAG + 数据库 schema + @ mentions）。 */
    public String buildChatPrompt(List<Map<String, Object>> nodes,
                                  List<Map<String, Object>> edges,
                                  String message,
                                  List<RagChunk> ragChunks,
                                  List<DbSchema> dbSchemas,
                                  List<MentionRef> mentions) {
        return chatPromptBuilder.buildChatPrompt(nodes, edges, message, ragChunks, dbSchemas, mentions);
    }

    // ============================================================
    // predict prompt（委派 PredictPromptBuilder）
    // ============================================================

    /**
     * 构造推演用的完整 prompt（system + user）。
     * <p>纯函数，不发起网络调用；orchestrator 把 user 部分原样写入 Scenario.rawPrompt 供前端展示。
     */
    public PredictPromptArtifact buildPredictPrompt(PredictRequest req) {
        return predictPromptBuilder.buildPredictPrompt(req);
    }

    /** 提取规则节点（type=rule）的可读摘要，单独列出方便 LLM 在推演时优先用规则。 */
    public String summarizeRules(List<Map<String, Object>> nodes) {
        return predictPromptBuilder.summarizeRules(nodes);
    }

    /**
     * 把 what-if 约束转成中文 prompt 文本：block / probability / force 三种模式各对应一段说明。
     * <p>probability 模式提示 LLM 把先验作为初值做贝叶斯更新。
     */
    public String summarizeConstraints(List<Constraint> cs, List<Map<String, Object>> nodes) {
        return predictPromptBuilder.summarizeConstraints(cs, nodes);
    }

    /** 把 seeds 列成 "id (label)" 格式，让 LLM 在 prompt 中直接看到种子的人类可读名称。 */
    public String summarizeSeeds(List<String> seeds, List<Map<String, Object>> nodes) {
        return predictPromptBuilder.summarizeSeeds(seeds, nodes);
    }

    // ============================================================
    // 图摘要 / 上下文截断（委派 GraphSummarizer）
    // ============================================================

    /** 把图谱压缩成 LLM 能读的可读文本（ASCII 表格风），节点 / 边各一段。 */
    public String summarizeGraph(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        return graphSummarizer.summarizeGraph(nodes, edges);
    }

    /**
     * 根据预算把大图谱裁剪成"以 seeds / rules / constraints 为中心的 N-hop 邻域"。
     */
    public TruncatedGraph truncateGraphForContext(List<Map<String, Object>> nodes,
                                                  List<Map<String, Object>> edges,
                                                  List<String> seeds,
                                                  List<Constraint> constraints) {
        return graphSummarizer.truncateGraphForContext(nodes, edges, seeds, constraints);
    }

    // ============================================================
    // 数据库 schema 序列化 / extract prompt（委派 SchemaPromptRenderer）
    // ============================================================

    /**
     * Compact 版 schema 渲染：chat 场景用，每张表只列重要列（PK/FK/unique + 前几列），
     * 外键单独成段。用 ASCII tree 让 LLM 容易解析。
     */
    public String renderSchemaCompact(DatabaseSchemaInfo s) {
        return schemaPromptRenderer.renderSchemaCompact(s);
    }

    /**
     * 构造"DB schema → ontology lineage"的完整 prompt。
     * <p>系统 prompt 用 {@link LlmPrompts#SCHEMA_TO_ONTOLOGY_SYSTEM}，user 部分把 schema 全量
     * 详尽展开（不像 chat 场景那样省略列），让 LLM 拿到最完整的"事实"。
     */
    public SchemaExtractPrompt buildSchemaExtractPrompt(DatabaseSchemaInfo schema,
                                                       String sourceName,
                                                       String extraHint) {
        return schemaPromptRenderer.buildSchemaExtractPrompt(schema, sourceName, extraHint);
    }

    /** 任务分解·阶段1（仅节点：实体+属性+约束）。委派 {@link SchemaPromptRenderer}。 */
    public SchemaExtractPrompt buildNodeStagePrompt(DatabaseSchemaInfo schema, String sourceName, String extraHint) {
        return schemaPromptRenderer.buildNodeStagePrompt(schema, sourceName, extraHint);
    }

    /** 任务分解·阶段2（仅边：关系+血缘，基于阶段1 已固定的节点）。委派 {@link SchemaPromptRenderer}。 */
    public SchemaExtractPrompt buildEdgeStagePrompt(DatabaseSchemaInfo schema, String sourceName,
                                                    String extraHint, JsonNode knownNodes) {
        return schemaPromptRenderer.buildEdgeStagePrompt(schema, sourceName, extraHint, knownNodes);
    }

    /** Full 版 schema 渲染：extract 场景用，每张表完整列出所有列 + 全部约束 + 全部外键。 */
    public String renderSchemaFull(DatabaseSchemaInfo s) {
        return schemaPromptRenderer.renderSchemaFull(s);
    }
}

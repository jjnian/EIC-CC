package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.repository.DataSourceRepository;
import com.tuiyan.backend.service.connector.JdbcConnectorService;
import com.tuiyan.backend.service.connector.JdbcConnectorService.DatabaseSchemaInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.ForeignKeyInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.TableInfo;
import com.tuiyan.backend.service.llm.GraphPromptBuilder;
import com.tuiyan.backend.service.llm.LlmCallLogger;
import com.tuiyan.backend.service.llm.LlmHttpClient;
import com.tuiyan.backend.support.IdSaltRewriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * 数据库 schema → 本体血缘图：与文档抽取分开实现的专用 facade。
 * <p>核心区别：
 * <ul>
 *   <li>输入是结构化事实（表 / 列 / FK / 唯一键），不需要切片去重，单次 LLM 调用即可；</li>
 *   <li>使用专门的 SCHEMA_TO_ONTOLOGY_SYSTEM prompt，规则确定，幻觉率低；</li>
 *   <li>LLM 输出后做"硬保底"——若 LLM 漏掉表或 FK，本服务会自动补齐基础节点 / 边，
 *       保证产物至少是一个完整的"表+FK"血缘图，质量不至于劣化。</li>
 * </ul>
 */
@Service
public class SchemaOntologyService {

    private static final Logger log = LoggerFactory.getLogger(SchemaOntologyService.class);

    // 大库一次性塞进 prompt 风险高：超过 80 张表时切分；普通场景一次过
    private static final int MAX_TABLES_PER_BATCH = 80;
    // 默认最多内省 200 张表，避免超大库拖死流程
    private static final int DEFAULT_TABLE_LIMIT = 200;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcConnectorService jdbc;
    private final DataSourceRepository dsRepo;
    private final LlmHttpClient http;
    private final LlmCallLogger callLogger;
    private final GraphPromptBuilder promptBuilder;

    public SchemaOntologyService(JdbcConnectorService jdbc,
                                 DataSourceRepository dsRepo,
                                 LlmHttpClient http,
                                 LlmCallLogger callLogger,
                                 GraphPromptBuilder promptBuilder) {
        this.jdbc = jdbc;
        this.dsRepo = dsRepo;
        this.http = http;
        this.callLogger = callLogger;
        this.promptBuilder = promptBuilder;
    }

    /** 进度回调，用于 SSE 上报"读 schema / 调 LLM / 后处理"等阶段。 */
    public interface StepSink {
        void emit(String key, String label);
    }

    /** 提取结果：与 chat / extract 端兼容的 {nodes, edges, ...} 形状。 */
    public record ExtractResult(JsonNode payload, String salt,
                                int tableCount, int fkCount,
                                int nodeCount, int edgeCount) {}

    /**
     * 从指定数据源拉 schema 并生成本体血缘图。
     * <p>返回的 JSON 满足 {@code {nodes:[], edges:[], reply:""}}，可被前端 import 流程直接合并。
     *
     * @param dsId 数据源 id（mysql/pgsql）
     * @param modelOverride 可选模型覆盖
     * @param configId 可选 LLM 配置 id
     * @param userHint 用户额外提示（如"重点关注订单链路"）
     * @param step 进度回调
     */
    public ExtractResult extractFromDataSource(String dsId,
                                               String modelOverride,
                                               String configId,
                                               String userHint,
                                               StepSink step) throws IOException {
        DataSourcePO po = dsRepo.findById(dsId);
        if (po == null) throw new IllegalArgumentException("数据源不存在: " + dsId);
        String kind = po.getKind();
        if (!"mysql".equals(kind) && !"pgsql".equals(kind)) {
            throw new IllegalArgumentException("仅支持 mysql / pgsql 数据源，当前: " + kind);
        }
        Map<String, Object> cfg = dsRepo.readConfig(po);
        String sourceName = po.getName() == null ? dsId : po.getName();

        step.emit("introspect_start", "正在连接「" + sourceName + "」内省 schema…");
        DatabaseSchemaInfo schema = jdbc.introspectSchema(kind, cfg, DEFAULT_TABLE_LIMIT);
        int tableCount = schema.tables().size();
        int fkCount = schema.tables().stream()
                .mapToInt(t -> t.foreignKeys().size()).sum();
        step.emit("introspect_done",
                "schema 内省完成：" + tableCount + " 张表 / " + fkCount + " 条外键");

        if (tableCount == 0) {
            throw new IllegalStateException("数据源「" + sourceName + "」没有可读取的表");
        }

        // 解析 LLM 配置（一次性，后续 batch 复用）
        LlmHttpClient.ResolvedConfig llmCfg = http.resolveConfig(modelOverride, configId);
        boolean anthropic = http.isAnthropic(llmCfg.baseURL(), llmCfg.modelName(), llmCfg.protocol());

        // 大库分批：每批 ≤ MAX_TABLES_PER_BATCH 张表，合并产物
        List<DatabaseSchemaInfo> batches = splitSchema(schema, MAX_TABLES_PER_BATCH);
        if (batches.size() > 1) {
            step.emit("batch_plan", "表数较多，将分 " + batches.size() + " 批送给大模型");
        }

        ObjectNode merged = null;
        for (int i = 0; i < batches.size(); i++) {
            DatabaseSchemaInfo batch = batches.get(i);
            String label = batches.size() == 1
                    ? "调用大模型生成本体（" + batch.tables().size() + " 张表）…"
                    : "调用大模型生成本体 第 " + (i + 1) + "/" + batches.size()
                        + " 批（" + batch.tables().size() + " 张表）…";
            step.emit("llm_call_" + i, label);
            ObjectNode part = callLlmOnce(batch, sourceName, userHint, llmCfg, anthropic);
            merged = (merged == null) ? part : mergeBatches(merged, part);
        }

        // ===== 硬保底：把 LLM 漏掉的表 / FK 自动补齐 =====
        step.emit("postprocess", "正在校验 LLM 产物并补齐遗漏的表/外键…");
        ObjectNode rectified = ensureCompleteness(merged, schema);
        rectified = sanitize(rectified);

        // 转换成 {nodes, edges, reply} 形状（与文档抽取一致），并加 salt 防止 id 冲突
        String salt = Long.toString(System.currentTimeMillis(), 36);
        JsonNode rewritten = IdSaltRewriter.applyImportSalt(rectified, salt);
        ObjectNode out = (ObjectNode) rewritten;
        out.put("reply", buildReplyText(sourceName, tableCount, fkCount,
                out.path("nodes").size(), out.path("edges").size()));

        int nodes = out.path("nodes").size();
        int edges = out.path("edges").size();
        step.emit("done", "完成：生成 " + nodes + " 个节点 / " + edges + " 条边");
        return new ExtractResult(out, salt, tableCount, fkCount, nodes, edges);
    }

    /** 单批调 LLM，返回 LLM 原始的 {add_nodes, add_edges} ObjectNode。 */
    private ObjectNode callLlmOnce(DatabaseSchemaInfo batch,
                                   String sourceName,
                                   String userHint,
                                   LlmHttpClient.ResolvedConfig cfg,
                                   boolean anthropic) throws IOException {
        GraphPromptBuilder.SchemaExtractPrompt prompts =
                promptBuilder.buildSchemaExtractPrompt(batch, sourceName, userHint);
        callLogger.logConversation("LLM-schema-extract", cfg.modelName(),
                prompts.system(), null, prompts.user(), null);

        String body = http.buildBody(cfg, prompts.system(), prompts.user(),
                null, null, false, true);
        HttpRequest req = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, body, cfg.rawUrl());

        long t0 = System.currentTimeMillis();
        HttpResponse<String> resp = http.sendHttp(req, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - t0;
        if (resp.statusCode() != 200) {
            log.error("[LLM-schema-extract] HTTP {} elapsed={}ms body={}",
                    resp.statusCode(), elapsed, resp.body());
            callLogger.logUpstreamError("schema-extract", resp.statusCode(), resp.body());
            http.metrics().recordCall(cfg.modelName(), elapsed, false);
            throw new RuntimeException("LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）");
        }
        http.metrics().recordCall(cfg.modelName(), elapsed, true);
        JsonNode root = objectMapper.readTree(resp.body());
        String content = http.stripJsonFence(http.extractContent(root, anthropic));
        callLogger.logLlmResponse("LLM-schema-extract", cfg.modelName(), elapsed, content);
        JsonNode parsed = objectMapper.readTree(content);
        if (!(parsed instanceof ObjectNode)) {
            throw new IllegalStateException("LLM 返回格式非对象，无法处理");
        }
        return (ObjectNode) parsed;
    }

    /** 切批：按字母表（来自 introspectSchema 已排序）顺序切，便于复现。 */
    private List<DatabaseSchemaInfo> splitSchema(DatabaseSchemaInfo s, int batchSize) {
        List<TableInfo> all = s.tables();
        if (all.size() <= batchSize) return List.of(s);
        List<DatabaseSchemaInfo> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += batchSize) {
            int end = Math.min(all.size(), i + batchSize);
            out.add(new DatabaseSchemaInfo(s.kind(), s.database(), all.subList(i, end)));
        }
        return out;
    }

    /** 合并两批的 add_nodes/add_edges：按 id 去重（同 id 时保留先到的）。 */
    private ObjectNode mergeBatches(ObjectNode a, ObjectNode b) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode nodes = objectMapper.createArrayNode();
        Set<String> nodeIds = new HashSet<>();
        for (JsonNode n : a.path("add_nodes")) {
            String id = n.path("id").asText("");
            if (id.isEmpty() || nodeIds.add(id)) nodes.add(n);
        }
        for (JsonNode n : b.path("add_nodes")) {
            String id = n.path("id").asText("");
            if (id.isEmpty() || nodeIds.add(id)) nodes.add(n);
        }
        ArrayNode edges = objectMapper.createArrayNode();
        Set<String> edgeIds = new HashSet<>();
        for (JsonNode e : a.path("add_edges")) {
            String id = e.path("id").asText("");
            if (id.isEmpty() || edgeIds.add(id)) edges.add(e);
        }
        for (JsonNode e : b.path("add_edges")) {
            String id = e.path("id").asText("");
            if (id.isEmpty() || edgeIds.add(id)) edges.add(e);
        }
        out.set("add_nodes", nodes);
        out.set("add_edges", edges);
        return out;
    }

    /**
     * 硬保底：保证每张表都有节点、每条 FK 都有边。LLM 出错或漏掉的，本方法用 schema 本身补齐。
     * <p>这是质量的最后一道防线——即使 LLM 完全失败，输出至少是一个朴素的"表+FK"图。
     */
    private ObjectNode ensureCompleteness(ObjectNode llmOut, DatabaseSchemaInfo schema) {
        if (llmOut == null) llmOut = objectMapper.createObjectNode();
        ArrayNode nodes = llmOut.has("add_nodes") && llmOut.get("add_nodes").isArray()
                ? (ArrayNode) llmOut.get("add_nodes") : objectMapper.createArrayNode();
        ArrayNode edges = llmOut.has("add_edges") && llmOut.get("add_edges").isArray()
                ? (ArrayNode) llmOut.get("add_edges") : objectMapper.createArrayNode();

        // 已有节点的 id 集合，及 tableName → nodeId 映射（用于补 FK 边时引用）
        Set<String> existingNodeIds = new HashSet<>();
        Map<String, String> tableToNodeId = new HashMap<>();
        for (JsonNode n : nodes) {
            String id = n.path("id").asText("");
            if (!id.isEmpty()) existingNodeIds.add(id);
            String evidence = n.path("evidence").asText("");
            // 优先用 evidence/label 推断对应的表名（LLM 可能改写过 label，但 evidence 我们要求是表名）
            for (TableInfo t : schema.tables()) {
                if (t.name().equalsIgnoreCase(evidence)
                        || (n.path("label").asText("").contains(t.name()))) {
                    tableToNodeId.putIfAbsent(t.name(), id);
                    break;
                }
            }
        }

        int fixedNodes = 0;
        int fixedEdges = 0;

        // 1) 补齐缺失的表节点
        for (TableInfo t : schema.tables()) {
            String stableId = "t_" + sanitize(t.name());
            if (tableToNodeId.containsKey(t.name())) continue;
            if (existingNodeIds.contains(stableId)) {
                tableToNodeId.put(t.name(), stableId);
                continue;
            }
            // 真的缺：本地构造一个完整节点
            nodes.add(buildFallbackNode(t, stableId));
            existingNodeIds.add(stableId);
            tableToNodeId.put(t.name(), stableId);
            fixedNodes++;
        }

        // 2) 补齐缺失的 FK 边（按 fromTable + fromCol + toTable + toCol 唯一性判断）
        Set<String> existingEdgeKeys = new HashSet<>();
        for (JsonNode e : edges) {
            String f = e.path("from").asText("");
            String tNode = e.path("to").asText("");
            String label = e.path("label").asText("");
            existingEdgeKeys.add(f + "->" + tNode + "#" + label);
        }
        for (TableInfo t : schema.tables()) {
            for (ForeignKeyInfo fk : t.foreignKeys()) {
                String fromNode = tableToNodeId.get(t.name());
                String toNode = tableToNodeId.get(fk.toTable());
                if (fromNode == null || toNode == null) continue;
                // 我们的 FK 边 label 约定：childTable.childCol → parentTable.parentCol
                String label = t.name() + "." + fk.fromColumn()
                        + " → " + fk.toTable() + "." + fk.toColumn();
                String key = fromNode + "->" + toNode + "#" + label;
                // LLM 可能用更复杂的 label，无法严格命中，所以再用更宽松的 from+to+col 启发式判断
                if (existingEdgeKeys.contains(key)) continue;
                if (hasFkEdge(edges, fromNode, toNode, fk.fromColumn(), fk.toColumn())) continue;
                edges.add(buildFallbackFkEdge(t, fk, fromNode, toNode));
                existingEdgeKeys.add(key);
                fixedEdges++;
            }
        }

        if (fixedNodes > 0 || fixedEdges > 0) {
            log.info("[schema-ontology] 保底补齐: 节点 +{} / 边 +{}", fixedNodes, fixedEdges);
        }

        // 转换字段名：本服务上游期望 {add_nodes, add_edges}（兼容 ExtractionLlmService），
        // 而下游 IdSaltRewriter 要求 add_nodes / add_edges，正好一致，不用改字段名。
        ObjectNode out = objectMapper.createObjectNode();
        out.set("add_nodes", nodes);
        out.set("add_edges", edges);
        return out;
    }

    /** 启发式判断 LLM 是否已经用别的 label/id 表示了同一条 FK 边。 */
    private boolean hasFkEdge(ArrayNode edges, String fromId, String toId,
                              String fromCol, String toCol) {
        for (JsonNode e : edges) {
            if (!fromId.equals(e.path("from").asText(""))) continue;
            if (!toId.equals(e.path("to").asText(""))) continue;
            String label = e.path("label").asText("");
            if (label.contains(fromCol) && label.contains(toCol)) return true;
        }
        return false;
    }

    /** 给缺失的表构造一个朴素但完整的节点。 */
    private ObjectNode buildFallbackNode(TableInfo t, String stableId) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("id", stableId);
        String label = t.comment() != null && !t.comment().isBlank()
                ? t.comment() + "(" + t.name() + ")"
                : t.name();
        n.put("label", label);
        n.put("type", inferType(t));
        n.put("source", "derived");
        n.put("evidence", t.name());
        n.put("confidence", 1.0);
        // attributes：把每一列都列出来（最多 30 列防爆炸）
        ArrayNode attrs = objectMapper.createArrayNode();
        int max = Math.min(t.columns().size(), 30);
        for (int i = 0; i < max; i++) {
            var c = t.columns().get(i);
            ObjectNode a = objectMapper.createObjectNode();
            a.put("name", c.name());
            a.put("valueSpace", normalizeType(c.dataType()));
            StringBuilder desc = new StringBuilder();
            if (c.primaryKey()) desc.append("[PK] ");
            if (c.comment() != null && !c.comment().isBlank()) desc.append(c.comment());
            a.put("description", desc.toString().trim());
            a.put("source", c.comment() != null && !c.comment().isBlank()
                    ? "derived" : "inferred");
            attrs.add(a);
        }
        n.set("attributes", attrs);
        // constraints：主键 + 唯一键
        ArrayNode cons = objectMapper.createArrayNode();
        List<String> pkCols = new ArrayList<>();
        for (var c : t.columns()) if (c.primaryKey()) pkCols.add(c.name());
        if (!pkCols.isEmpty()) {
            ObjectNode pk = objectMapper.createObjectNode();
            pk.put("kind", "cardinality");
            pk.put("note", "主键: " + String.join(",", pkCols));
            pk.put("source", "derived");
            cons.add(pk);
        }
        for (var uk : t.uniqueKeys()) {
            ObjectNode u = objectMapper.createObjectNode();
            u.put("kind", "custom");
            u.put("note", "业务唯一键: " + uk.name() + "(" + String.join(",", uk.columns()) + ")");
            u.put("source", "derived");
            cons.add(u);
        }
        n.set("constraints", cons);
        return n;
    }

    /** 用表名/列名启发式分类 type（与 SCHEMA_TO_ONTOLOGY_SYSTEM 中的规则保持一致）。 */
    private static String inferType(TableInfo t) {
        String n = t.name() == null ? "" : t.name().toLowerCase();
        String cm = t.comment() == null ? "" : t.comment();
        if (n.matches("^(rule|policy|config|setting|param|dict|enum)s?_?.*")
                || cm.contains("规则") || cm.contains("配置") || cm.contains("字典") || cm.contains("枚举")) {
            return "rule";
        }
        if (n.endsWith("_log") || n.endsWith("_history") || n.endsWith("_event")
                || n.endsWith("_audit") || n.endsWith("_record") || n.endsWith("_trace")
                || n.endsWith("_journal") || n.contains("event") || n.contains("action")) {
            return "event";
        }
        if (n.endsWith("_task") || n.endsWith("_job") || n.endsWith("_step")
                || n.endsWith("_stage") || n.endsWith("_pipeline") || n.endsWith("_run")) {
            return "process";
        }
        if (n.startsWith("ext_") || n.startsWith("third_") || n.startsWith("partner_")
                || cm.contains("外部") || cm.contains("第三方")) {
            return "external";
        }
        if (n.startsWith("dim_") || n.endsWith("_mapping") || n.endsWith("_relation")
                || n.endsWith("_dict") || n.endsWith("_meta")) {
            return "data";
        }
        // 复合主键全部都是 FK → 纯关联表
        int pkCount = 0;
        for (var c : t.columns()) if (c.primaryKey()) pkCount++;
        if (pkCount >= 2) {
            Set<String> fkCols = new HashSet<>();
            for (var fk : t.foreignKeys()) fkCols.add(fk.fromColumn());
            boolean allFk = true;
            for (var c : t.columns()) {
                if (!c.primaryKey()) continue;
                if (!fkCols.contains(c.name())) { allFk = false; break; }
            }
            if (allFk) return "data";
        }
        return "entity";
    }

    private static String normalizeType(String sqlType) {
        if (sqlType == null) return "string";
        String t = sqlType.toLowerCase();
        if (t.contains("int") || t.contains("decimal") || t.contains("numeric")
                || t.contains("double") || t.contains("float") || t.contains("real")) {
            return "number";
        }
        if (t.contains("date") || t.contains("time") || t.contains("timestamp")) {
            return "date";
        }
        if (t.contains("bool") || t.equals("tinyint(1)")) return "boolean";
        if (t.contains("json") || t.contains("jsonb")) return "json";
        return "string";
    }

    /** 给缺失的 FK 构造一条朴素但完整的边。 */
    private ObjectNode buildFallbackFkEdge(TableInfo child, ForeignKeyInfo fk,
                                           String fromId, String toId) {
        ObjectNode e = objectMapper.createObjectNode();
        e.put("id", "e_fk_" + sanitize(child.name()) + "_" + sanitize(fk.fromColumn())
                + "__" + sanitize(fk.toTable()));
        e.put("from", fromId);
        e.put("to", toId);
        // 与 SCHEMA_TO_ONTOLOGY_SYSTEM 的 rel_type 决策保持一致的简化版
        String rel;
        String toLower = fk.toTable().toLowerCase();
        if (toLower.startsWith("dim_") || toLower.endsWith("_dict")) {
            rel = "derived_from";
        } else {
            rel = "derived_from"; // 默认下游派生自上游主表
        }
        e.put("rel_type", rel);
        e.put("label", child.name() + "." + fk.fromColumn()
                + " → " + fk.toTable() + "." + fk.toColumn());
        e.put("source", "derived");
        e.put("evidence", fk.constraintName() == null ? "FK" : fk.constraintName());
        e.put("confidence", 1.0);
        e.put("rule_driven", false);
        ArrayNode cons = objectMapper.createArrayNode();
        ObjectNode c = objectMapper.createObjectNode();
        c.put("kind", "cardinality");
        c.put("note", "N:1 (FK)");
        c.put("source", "derived");
        cons.add(c);
        e.set("constraints", cons);
        return e;
    }

    /** 再做一遍校验：去掉悬空边、自环、重复边。 */
    private ObjectNode sanitize(ObjectNode draft) {
        ArrayNode nodes = (ArrayNode) draft.path("add_nodes");
        ArrayNode edges = (ArrayNode) draft.path("add_edges");
        Set<String> ids = new HashSet<>();
        for (JsonNode n : nodes) {
            String id = n.path("id").asText("");
            if (!id.isEmpty()) ids.add(id);
        }
        ArrayNode cleaned = objectMapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        int dropped = 0;
        for (JsonNode e : edges) {
            String f = e.path("from").asText("");
            String t = e.path("to").asText("");
            if (f.isEmpty() || t.isEmpty() || !ids.contains(f) || !ids.contains(t)) {
                dropped++;
                continue;
            }
            if (f.equals(t)) {
                // 允许自引用（employee.manager_id → employee.id）
                cleaned.add(e);
                continue;
            }
            String relKey = e.path("rel_type").asText(e.path("label").asText(""));
            String sig = f + "->" + t + "#" + relKey;
            if (!seen.add(sig)) { dropped++; continue; }
            cleaned.add(e);
        }
        if (dropped > 0) {
            log.info("[schema-ontology] sanitize 丢弃 {} 条非法边", dropped);
        }
        draft.set("add_edges", cleaned);
        return draft;
    }

    private static String sanitize(String s) {
        if (s == null) return "_";
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }

    private static String buildReplyText(String sourceName, int tableCount, int fkCount,
                                         int nodeCount, int edgeCount) {
        return String.format(
                "已根据数据源「%s」生成本体血缘图：%d 张表 / %d 条外键 → %d 个节点 / %d 条关系。"
                        + "节点已携带列级 attributes 与主键/唯一键 constraints；外键直接还原为 derived_from / composed_of / triggers 边。"
                        + "如需补全推断关系或调整分类，可继续在 chat 中告诉我。",
                sourceName, tableCount, fkCount, nodeCount, edgeCount);
    }
}

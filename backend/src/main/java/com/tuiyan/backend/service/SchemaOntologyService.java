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

        // ===== 事实校验：保留语义节点，但要求能追溯到真实表 / 列 / FK =====
        step.emit("fact_check", "正在校验语义节点来源，删除无法追溯到 schema 的内容…");
        FactCheckResult fc = factCheck(merged, schema);
        ObjectNode rectified = fc.cleaned;
        if (fc.removedNodes + fc.removedAttrs + fc.removedEdges + fc.demotedEdges > 0) {
            step.emit("fact_check_summary",
                    String.format("已清理 LLM 幻觉: 删除 %d 个无来源节点 · %d 个无来源属性 · %d 条无来源关系 · 降级 %d 条弱证据关系",
                            fc.removedNodes, fc.removedAttrs, fc.removedEdges, fc.demotedEdges));
        }

        // ===== 覆盖保底：补齐完全未被任何语义节点/关系覆盖的表与 FK =====
        step.emit("postprocess", "正在检查 schema 覆盖率并补齐遗漏来源…");
        rectified = ensureCoverage(rectified, schema);
        rectified = sanitize(rectified);

        // 转换成 {nodes, edges, reply} 形状（与文档抽取一致），并加 salt 防止 id 冲突
        String salt = Long.toString(System.currentTimeMillis(), 36);
        JsonNode rewritten = IdSaltRewriter.applyImportSalt(rectified, salt);
        ObjectNode out = (ObjectNode) rewritten;
        // 给每个节点/边打上数据源 + 库名来源（整张图同源，统一 stamp）
        String derivedDatabase = schema.database();
        stampSource(out.path("nodes"), sourceName, derivedDatabase);
        stampSource(out.path("edges"), sourceName, derivedDatabase);
        out.put("reply", buildReplyText(sourceName, tableCount, fkCount,
                out.path("nodes").size(), out.path("edges").size(), fc));

        int nodes = out.path("nodes").size();
        int edges = out.path("edges").size();
        step.emit("done", "完成：生成 " + nodes + " 个节点 / " + edges + " 条边");
        return new ExtractResult(out, salt, tableCount, fkCount, nodes, edges);
    }

    /** 给 nodes/edges 数组里每个对象补 derived_source / derived_database。整张图同源时统一打标。 */
    private void stampSource(JsonNode arr, String source, String database) {
        if (!(arr instanceof ArrayNode list)) return;
        for (JsonNode n : list) {
            if (!(n instanceof ObjectNode obj)) continue;
            if (source != null && !source.isBlank()) obj.put("derived_source", source);
            if (database != null && !database.isBlank()) obj.put("derived_database", database);
        }
    }

    /** 事实校验结果统计,便于把"删了多少幻觉内容"告诉用户。 */
    public record FactCheckResult(ObjectNode cleaned,
                                  int removedNodes,
                                  int removedAttrs,
                                  int removedEdges,
                                  int demotedEdges) {}

    /**
     * 事实校验 — 允许 LLM 做语义合并/改名,但必须保留可追溯的 schema 来源。
     * <ol>
     *   <li>节点必须通过 {@code derived_tables} / evidence / 旧式 t_ id 至少命中一张真实表;</li>
     *   <li>attributes 可以使用业务名,但 {@code column} 必须命中该节点来源表中的真实列;</li>
     *   <li>derived 边必须能由两端来源表之间的真实 FK,或一张真实关联表支撑;</li>
     *   <li>无法追溯到 schema 的内容删除,弱命名证据降级为 inferred。</li>
     * </ol>
     */
    private FactCheckResult factCheck(ObjectNode llmOut, DatabaseSchemaInfo schema) {
        ArrayNode nodes = llmOut != null && llmOut.has("add_nodes") && llmOut.get("add_nodes").isArray()
                ? (ArrayNode) llmOut.get("add_nodes") : objectMapper.createArrayNode();
        ArrayNode edges = llmOut != null && llmOut.has("add_edges") && llmOut.get("add_edges").isArray()
                ? (ArrayNode) llmOut.get("add_edges") : objectMapper.createArrayNode();

        // 真实表索引: sanitized 名称 → TableInfo
        Map<String, TableInfo> realTables = new HashMap<>();
        for (TableInfo t : schema.tables()) realTables.put(sanitize(t.name()), t);
        ArrayNode cleanedNodes = objectMapper.createArrayNode();
        Set<String> keptNodeIds = new HashSet<>();
        Map<String, List<TableInfo>> nodeIdToTables = new HashMap<>();
        int removedNodes = 0;
        int removedAttrs = 0;
        for (JsonNode n : nodes) {
            ObjectNode copy = n.deepCopy();
            String id = copy.path("id").asText("");
            List<TableInfo> sourceTables = resolveTables(copy, realTables);
            if (sourceTables.isEmpty()) {
                removedNodes++;
                log.info("[factCheck] 删除虚构节点 id={} label={}", id, copy.path("label").asText(""));
                continue;
            }
            setDerivedTables(copy, sourceTables);
            // 校验 attributes
            JsonNode attrs = copy.path("attributes");
            if (attrs.isArray() && !attrs.isEmpty()) {
                Set<String> realCols = columnNames(sourceTables);
                ArrayNode keptAttrs = objectMapper.createArrayNode();
                for (JsonNode a : attrs) {
                    if (!(a instanceof ObjectNode attr)) continue;
                    String col = attr.path("column").asText("");
                    String legacyName = attr.path("name").asText("");
                    String backingCol = col.isBlank() ? legacyName : col;
                    if (backingCol.isBlank() || !realCols.contains(backingCol.toLowerCase())) {
                        removedAttrs++;
                        log.debug("[factCheck] 删除无来源属性 node={} attr={} column={}", id, legacyName, col);
                        continue;
                    }
                    if (col.isBlank()) attr.put("column", backingCol);
                    keptAttrs.add(attr);
                }
                copy.set("attributes", keptAttrs);
            }
            cleanedNodes.add(copy);
            if (!id.isEmpty()) {
                keptNodeIds.add(id);
                nodeIdToTables.put(id, sourceTables);
            }
        }

        ArrayNode cleanedEdges = objectMapper.createArrayNode();
        int removedEdges = 0;
        int demotedEdges = 0;
        for (JsonNode e : edges) {
            String f = e.path("from").asText("");
            String t = e.path("to").asText("");
            if (!keptNodeIds.contains(f) || !keptNodeIds.contains(t)) {
                removedEdges++;
                continue;
            }
            List<TableInfo> fromTables = nodeIdToTables.getOrDefault(f, List.of());
            List<TableInfo> toTables = nodeIdToTables.getOrDefault(t, List.of());
            if (fromTables.isEmpty() || toTables.isEmpty()) {
                removedEdges++;
                continue;
            }
            String src = e.path("source").asText("derived");
            String label = e.path("label").asText("");
            ObjectNode copy = e.deepCopy();

            // 仅对 derived / 默认 source 做严格 FK 校验
            boolean isDerived = "derived".equalsIgnoreCase(src) || src.isEmpty();
            if (!isDerived) {
                if (!hasNamingHint(fromTables, toTables)) {
                    removedEdges++;
                    log.info("[factCheck] 删除无命名根据的 inferred 边: {}->{}", f, t);
                    continue;
                }
                ensureEdgeDerivedTables(copy, fromTables, toTables);
                cleanedEdges.add(copy);
                continue;
            }

            boolean ok = isSupportedByRealFk(fromTables, toTables)
                    || isSupportedByJunctionTable(copy, realTables);
            if (ok) {
                ensureEdgeDerivedTables(copy, fromTables, toTables);
                cleanedEdges.add(copy);
                continue;
            }

            // 没找到 FK: 看是否符合 Rule 4 命名条件;符合 → 降级为 inferred,否则删
            if (hasNamingHint(fromTables, toTables)) {
                copy.put("source", "inferred");
                copy.put("confidence", 0.4);
                ensureEdgeDerivedTables(copy, fromTables, toTables);
                String existingEv = copy.path("evidence").asText("");
                if (existingEv.isBlank() || "FK".equalsIgnoreCase(existingEv)) {
                    copy.put("evidence", "naming");
                }
                if (label.isBlank() || label.contains("→")) {
                    copy.put("label", copy.path("label").asText("按命名推断的关系"));
                }
                cleanedEdges.add(copy);
                demotedEdges++;
                log.info("[factCheck] 降级无 FK 但有命名暗示的边: {}->{}", f, t);
            } else {
                removedEdges++;
                log.info("[factCheck] 删除既无 FK 又无命名暗示的虚构边: {}->{} (label={})",
                        f, t, label);
            }
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.set("add_nodes", cleanedNodes);
        out.set("add_edges", cleanedEdges);
        if (removedNodes + removedAttrs + removedEdges + demotedEdges > 0) {
            log.info("[factCheck] 总结: 删 {} 节点 / {} 属性 / {} 边, 降级 {} 边",
                    removedNodes, removedAttrs, removedEdges, demotedEdges);
        }
        return new FactCheckResult(out, removedNodes, removedAttrs, removedEdges, demotedEdges);
    }

    /** 将一个 LLM 节点解析回 schema 真实表集合;优先使用 derived_tables,兼容旧式 t_<table> 节点。 */
    private List<TableInfo> resolveTables(ObjectNode node, Map<String, TableInfo> realTables) {
        LinkedHashMap<String, TableInfo> out = new LinkedHashMap<>();
        JsonNode derived = node.path("derived_tables");
        if (derived.isArray()) {
            for (JsonNode one : derived) {
                TableInfo t = realTables.get(sanitize(one.asText("")));
                if (t != null) out.putIfAbsent(sanitize(t.name()), t);
            }
        }
        String id = node.path("id").asText("");
        if (id.startsWith("t_")) {
            TableInfo t = realTables.get(id.substring(2));
            if (t != null) out.putIfAbsent(sanitize(t.name()), t);
        }
        String evidence = node.path("evidence").asText("");
        if (!evidence.isBlank()) {
            TableInfo t = realTables.get(sanitize(evidence));
            if (t != null) out.putIfAbsent(sanitize(t.name()), t);
        }
        String label = node.path("label").asText("");
        if (!label.isBlank()) {
            String lower = label.toLowerCase();
            for (Map.Entry<String, TableInfo> en : realTables.entrySet()) {
                String tn = en.getValue().name().toLowerCase();
                if (lower.contains(tn)) out.putIfAbsent(en.getKey(), en.getValue());
            }
        }
        return new ArrayList<>(out.values());
    }

    private void setDerivedTables(ObjectNode node, List<TableInfo> tables) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (TableInfo t : tables) arr.add(t.name());
        node.set("derived_tables", arr);
    }

    private Set<String> columnNames(List<TableInfo> tables) {
        Set<String> cols = new HashSet<>();
        for (TableInfo t : tables) {
            for (var c : t.columns()) cols.add(c.name().toLowerCase());
        }
        return cols;
    }

    private boolean isSupportedByRealFk(List<TableInfo> a, List<TableInfo> b) {
        for (TableInfo left : a) {
            for (TableInfo right : b) {
                if (hasDeclaredFk(left, right) || hasDeclaredFk(right, left)) return true;
            }
        }
        return false;
    }

    private boolean hasDeclaredFk(TableInfo child, TableInfo parent) {
        for (ForeignKeyInfo fk : child.foreignKeys()) {
            if (fk.toTable().equalsIgnoreCase(parent.name())) return true;
        }
        return false;
    }

    /**
     * 纯关联 / junction 表判断：
     * <ul>
     *   <li>主键列数量不少于 2，且主键列全部都是外键列；</li>
     *   <li>表中没有额外的业务列，最多只保留极少量元数据列；</li>
     *   <li>这类表通常用于 N:M 关联或映射，不应被当作独立业务实体。</li>
     * </ul>
     */
    private boolean isPureJunctionTable(TableInfo t) {
        if (t == null) return false;

        Set<String> fkCols = new HashSet<>();
        for (ForeignKeyInfo fk : t.foreignKeys()) {
            if (fk.fromColumn() != null) fkCols.add(fk.fromColumn().toLowerCase());
        }

        List<String> pkCols = new ArrayList<>();
        for (var c : t.columns()) {
            if (c.primaryKey()) pkCols.add(c.name().toLowerCase());
        }

        if (pkCols.size() < 2) return false;

        // 复合主键必须全部是外键列
        for (String pk : pkCols) {
            if (!fkCols.contains(pk)) return false;
        }

        // 允许少量常见元数据列，但不能出现明显业务扩展列
        int nonTechnicalCols = 0;
        for (var c : t.columns()) {
            String cn = c.name().toLowerCase();
            boolean technical = c.primaryKey()
                    || fkCols.contains(cn)
                    || cn.equals("created_at")
                    || cn.equals("updated_at")
                    || cn.equals("deleted_at")
                    || cn.equals("tenant_id")
                    || cn.equals("remark")
                    || cn.equals("remarks")
                    || cn.equals("sort")
                    || cn.equals("sort_order");
            if (!technical) nonTechnicalCols++;
        }
        return nonTechnicalCols == 0;
    }

    private boolean isSupportedByJunctionTable(ObjectNode edge, Map<String, TableInfo> realTables) {
        List<TableInfo> edgeTables = resolveEdgeTables(edge, realTables);
        for (TableInfo t : edgeTables) {
            if (isPureJunctionTable(t) || t.foreignKeys().size() >= 2) return true;
        }
        return false;
    }

    private List<TableInfo> resolveEdgeTables(ObjectNode edge, Map<String, TableInfo> realTables) {
        LinkedHashMap<String, TableInfo> out = new LinkedHashMap<>();
        JsonNode derived = edge.path("derived_tables");
        if (derived.isArray()) {
            for (JsonNode one : derived) {
                TableInfo t = realTables.get(sanitize(one.asText("")));
                if (t != null) out.putIfAbsent(sanitize(t.name()), t);
            }
        }
        return new ArrayList<>(out.values());
    }

    private void ensureEdgeDerivedTables(ObjectNode edge, List<TableInfo> fromTables, List<TableInfo> toTables) {
        if (edge.has("derived_tables") && edge.get("derived_tables").isArray() && !edge.get("derived_tables").isEmpty()) {
            return;
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (TableInfo t : fromTables) names.add(t.name());
        for (TableInfo t : toTables) names.add(t.name());
        ArrayNode arr = objectMapper.createArrayNode();
        for (String name : names) arr.add(name);
        edge.set("derived_tables", arr);
    }

    /** 命名暗示:任一来源表含有另一来源表的 *_id/_code/_no 风格列。 */
    private boolean hasNamingHint(List<TableInfo> a, List<TableInfo> b) {
        for (TableInfo left : a) {
            for (TableInfo right : b) {
                if (hasNamingHint(left, right) || hasNamingHint(right, left)) return true;
            }
        }
        return false;
    }

    private boolean hasNamingHint(TableInfo childT, TableInfo parentT) {
        String pn = parentT.name().toLowerCase();
        for (var c : childT.columns()) {
            String cn = c.name().toLowerCase();
            if (cn.equals(pn + "_id") || cn.equals(pn + "_code") || cn.equals(pn + "_no")) return true;
            String singular = pn.endsWith("s") ? pn.substring(0, pn.length() - 1) : pn + "s";
            if (cn.equals(singular + "_id") || cn.equals(singular + "_code") || cn.equals(singular + "_no")) return true;
        }
        return false;
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
     * 覆盖保底：允许语义节点合并多张表,只补齐完全没有被任何节点/边覆盖的 schema 来源。
     * <p>FK 不再强制一条 FK 一条边；若 FK 两端已经被同一语义节点合并,视为已覆盖。
     */
    private ObjectNode ensureCoverage(ObjectNode llmOut, DatabaseSchemaInfo schema) {
        if (llmOut == null) llmOut = objectMapper.createObjectNode();
        ArrayNode nodes = llmOut.has("add_nodes") && llmOut.get("add_nodes").isArray()
                ? (ArrayNode) llmOut.get("add_nodes") : objectMapper.createArrayNode();
        ArrayNode edges = llmOut.has("add_edges") && llmOut.get("add_edges").isArray()
                ? (ArrayNode) llmOut.get("add_edges") : objectMapper.createArrayNode();

        Map<String, TableInfo> realTables = new HashMap<>();
        for (TableInfo t : schema.tables()) realTables.put(sanitize(t.name()), t);

        Set<String> existingNodeIds = new HashSet<>();
        Map<String, String> tableToNodeId = new HashMap<>();
        Set<String> coveredTables = new HashSet<>();
        for (JsonNode n : nodes) {
            String id = n.path("id").asText("");
            if (!id.isEmpty()) existingNodeIds.add(id);
            if (n instanceof ObjectNode obj) {
                for (TableInfo t : resolveTables(obj, realTables)) {
                    coveredTables.add(sanitize(t.name()));
                    if (!id.isEmpty()) tableToNodeId.putIfAbsent(t.name(), id);
                }
            }
        }
        for (JsonNode e : edges) {
            if (!(e instanceof ObjectNode obj)) continue;
            for (TableInfo t : resolveEdgeTables(obj, realTables)) coveredTables.add(sanitize(t.name()));
        }

        int fixedNodes = 0;
        int fixedEdges = 0;

        // 1) 只补齐完全没被节点/边覆盖的表
        for (TableInfo t : schema.tables()) {
            String stableId = "t_" + sanitize(t.name());
            if (coveredTables.contains(sanitize(t.name()))) continue;
            if (existingNodeIds.contains(stableId)) {
                tableToNodeId.put(t.name(), stableId);
                coveredTables.add(sanitize(t.name()));
                continue;
            }
            nodes.add(buildFallbackNode(t, stableId));
            existingNodeIds.add(stableId);
            tableToNodeId.put(t.name(), stableId);
            coveredTables.add(sanitize(t.name()));
            fixedNodes++;
        }

        // 2) FK 覆盖：两端被同一语义节点合并则跳过；不同节点之间无关系时补兜底边
        for (TableInfo t : schema.tables()) {
            for (ForeignKeyInfo fk : t.foreignKeys()) {
                String fromNode = tableToNodeId.get(t.name());
                String toNode = tableToNodeId.get(fk.toTable());
                if (fromNode == null || toNode == null) continue;
                if (fromNode.equals(toNode)) continue;
                if (hasFkCoverageEdge(edges, fromNode, toNode, t.name(), fk.toTable(), fk.fromColumn(), fk.toColumn())) {
                    continue;
                }
                edges.add(buildFallbackFkEdge(t, fk, fromNode, toNode));
                fixedEdges++;
            }
        }

        if (fixedNodes > 0 || fixedEdges > 0) {
            log.info("[schema-ontology] 覆盖保底补齐: 节点 +{} / 边 +{}", fixedNodes, fixedEdges);
        }

        // 转换字段名：本服务上游期望 {add_nodes, add_edges}（兼容 ExtractionLlmService），
        // 而下游 IdSaltRewriter 要求 add_nodes / add_edges，正好一致，不用改字段名。
        ObjectNode out = objectMapper.createObjectNode();
        out.set("add_nodes", nodes);
        out.set("add_edges", edges);
        return out;
    }

    /** 启发式判断 LLM 是否已经用语义关系覆盖了同一条 FK。 */
    private boolean hasFkCoverageEdge(ArrayNode edges, String fromId, String toId,
                                      String fromTable, String toTable,
                                      String fromCol, String toCol) {
        for (JsonNode e : edges) {
            String from = e.path("from").asText("");
            String to = e.path("to").asText("");
            if (!((fromId.equals(from) && toId.equals(to)) || (fromId.equals(to) && toId.equals(from)))) continue;
            if (edgeDerivedTablesContain(e, fromTable, toTable)) return true;
            String label = e.path("label").asText("");
            if (label.contains(fromCol) && label.contains(toCol)) return true;
        }
        return false;
    }

    private boolean edgeDerivedTablesContain(JsonNode edge, String a, String b) {
        JsonNode arr = edge.path("derived_tables");
        if (!arr.isArray()) return false;
        Set<String> names = new HashSet<>();
        for (JsonNode one : arr) names.add(sanitize(one.asText("")));
        return names.contains(sanitize(a)) && names.contains(sanitize(b));
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
        ArrayNode derivedTables = objectMapper.createArrayNode();
        derivedTables.add(t.name());
        n.set("derived_tables", derivedTables);
        // attributes：把每一列都列出来（最多 30 列防爆炸）
        ArrayNode attrs = objectMapper.createArrayNode();
        int max = Math.min(t.columns().size(), 30);
        for (int i = 0; i < max; i++) {
            var c = t.columns().get(i);
            ObjectNode a = objectMapper.createObjectNode();
            String attrName = c.comment() != null && !c.comment().isBlank() ? c.comment() : c.name();
            a.put("name", attrName);
            a.put("column", c.name());
            a.put("valueSpace", normalizeType(c.dataType()));
            StringBuilder desc = new StringBuilder();
            if (c.primaryKey()) desc.append("[PK] ");
            if (c.comment() != null && !c.comment().isBlank()) desc.append(c.comment());
            a.put("description", desc.toString().trim());
            a.put("source", "derived");
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
        ArrayNode derivedTables = objectMapper.createArrayNode();
        derivedTables.add(child.name());
        derivedTables.add(fk.toTable());
        e.set("derived_tables", derivedTables);
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
                                         int nodeCount, int edgeCount, FactCheckResult fc) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "已根据数据源「%s」生成本体血缘图：%d 张表 / %d 条外键 → %d 个节点 / %d 条关系。",
                sourceName, tableCount, fkCount, nodeCount, edgeCount));
        if (fc != null && (fc.removedNodes + fc.removedAttrs + fc.removedEdges + fc.demotedEdges) > 0) {
            sb.append("\n\n📋 事实校验报告（防止 LLM 天马行空）：");
            if (fc.removedNodes > 0) sb.append("\n  · 删除 ").append(fc.removedNodes).append(" 个虚构表节点（不在真实 schema 中）");
            if (fc.removedAttrs > 0) sb.append("\n  · 删除 ").append(fc.removedAttrs).append(" 个虚构属性（不在真实列中）");
            if (fc.removedEdges > 0) sb.append("\n  · 删除 ").append(fc.removedEdges).append(" 条虚构边（无 FK、无命名暗示）");
            if (fc.demotedEdges > 0) sb.append("\n  · 降级 ").append(fc.demotedEdges).append(" 条无 FK 但有命名暗示的边为 inferred / confidence=0.4");
        } else {
            sb.append("\n\n✅ 事实校验通过：本次产物全部锚定到真实表/列/外键，无幻觉内容。");
        }
        sb.append("\n\n节点已携带列级 attributes 与主键/唯一键 constraints；外键直接还原为 derived_from / composed_of / triggers 边。");
        return sb.toString();
    }
}

package com.tuiyan.backend.service.llm;

import com.tuiyan.backend.model.Constraint;
import com.tuiyan.backend.model.MentionRef;
import com.tuiyan.backend.model.PredictRequest;
import com.tuiyan.backend.service.connector.JdbcConnectorService.ColumnInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.DatabaseSchemaInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.ForeignKeyInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.TableInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.UniqueKeyInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱 → LLM Prompt 的纯函数集合：图谱摘要、上下文截断、各业务 user prompt 拼接。
 * <p>不持有任何状态、不发起 IO，单纯把"业务输入"转成"LLM 看的人类可读文本"。
 */
@Component
public class GraphPromptBuilder {

    // 单次推演给 LLM 的图谱预算
    private static final int CONTEXT_NODE_BUDGET = 120;
    private static final int CONTEXT_EDGE_BUDGET = 240;
    // 从 mandatory 节点向外扩散的跳数；3 跳通常足够覆盖核心因果链
    private static final int CONTEXT_HOPS = 3;

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
        StringBuilder sb = new StringBuilder();

        // ===== @ 引用块: 让 LLM 在最显眼的位置看到"用户重点关注"的对象 =====
        // 注意: 数据源类型的 mention 不在这里重复(它的完整 schema 已经在 dbSchemas 里),
        // 这里只列节点/关系/图谱,让 LLM 把焦点聚到这些已存在的对象上而非另起炉灶。
        String mentionBlock = renderMentions(mentions, nodes, edges);
        if (!mentionBlock.isEmpty()) {
            sb.append(mentionBlock);
        }

        // 数据库 schema 上下文(放在最前面,作为强结构化输入提示)
        if (dbSchemas != null && !dbSchemas.isEmpty()) {
            sb.append("可参考的数据库表结构 (来自当前工作空间已接入的数据源，请据此设计实体/关系):\n");
            for (DbSchema s : dbSchemas) {
                sb.append("- 数据源「").append(s.sourceName()).append("」 (")
                  .append(s.kind()).append(", 库: ").append(s.database()).append("):\n");
                // 优先用结构化详情；没有就退回到只有表名
                if (s.detail() != null && s.detail().tables() != null
                        && !s.detail().tables().isEmpty()) {
                    sb.append(renderSchemaCompact(s.detail()));
                } else {
                    sb.append("  Tables: ");
                    List<String> ts = s.tables();
                    if (ts == null || ts.isEmpty()) {
                        sb.append("(无)\n");
                    } else {
                        int max = Math.min(40, ts.size());
                        for (int i = 0; i < max; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(ts.get(i));
                        }
                        if (ts.size() > max) sb.append(" … 共 ").append(ts.size()).append(" 张表");
                        sb.append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        // RAG 上下文
        if (ragChunks != null && !ragChunks.isEmpty()) {
            sb.append("相关数据源内容 (按相关度排序，请参考这些内容来提取实体和关系):\n");
            for (RagChunk chunk : ragChunks) {
                sb.append("--- 来源: ").append(chunk.sourceName())
                  .append(" (相关度: ").append(String.format("%.2f", chunk.score())).append(") ---\n");
                sb.append(chunk.content()).append("\n\n");
            }
        }

        boolean hasGraph = (nodes != null && !nodes.isEmpty()) || (edges != null && !edges.isEmpty());
        if (hasGraph) {
            // 节点/关系 mention → 用作子图聚焦的 anchor;大图时只保留这些节点的 N-hop 邻域
            List<String> anchorIds = collectAnchorNodeIds(mentions, edges);
            List<Map<String, Object>> nodesForPrompt = nodes;
            List<Map<String, Object>> edgesForPrompt = edges;
            int totalNodes = nodes == null ? 0 : nodes.size();
            int totalEdges = edges == null ? 0 : edges.size();
            boolean focused = false;
            if (!anchorIds.isEmpty() && (totalNodes > CONTEXT_NODE_BUDGET / 2
                    || totalEdges > CONTEXT_EDGE_BUDGET / 2)) {
                TruncatedGraph tg = truncateGraphForContext(nodes, edges, anchorIds, null);
                if (tg.droppedNodes() > 0 || tg.droppedEdges() > 0) {
                    nodesForPrompt = tg.nodes();
                    edgesForPrompt = tg.edges();
                    focused = true;
                    sb.append("(已根据 @ 引用聚焦到 ").append(anchorIds.size())
                      .append(" 个锚点节点的 ").append(CONTEXT_HOPS).append("-hop 邻域: 保留 ")
                      .append(nodesForPrompt.size()).append(" / ").append(totalNodes).append(" 节点 + ")
                      .append(edgesForPrompt.size()).append(" / ").append(totalEdges).append(" 边)\n\n");
                }
            }
            sb.append("Existing ontology graph (the user is incrementally extending this — do NOT recreate any of these; reuse the ids exactly when you need to reference them):\n");
            sb.append(summarizeGraph(nodesForPrompt, edgesForPrompt));
            sb.append("\nIncremental update rules:\n");
            sb.append("  - In add_nodes, include ONLY genuinely new entities/events/rules not already present above.\n");
            sb.append("  - If a concept already exists above, reuse its existing id in add_edges instead of creating a duplicate node.\n");
            sb.append("  - In add_edges, 'from'/'to' may reference existing node ids OR ids of nodes in your own add_nodes list.\n");
            sb.append("  - Do not emit an edge that already exists above with the same (from, to, label).\n");
            if (focused) {
                sb.append("  - 用户已通过 @ 锚定了关注的节点;请优先围绕它们补全实体/关系,不要漂到与锚点无关的领域。\n");
            }
            sb.append("\n");
        }
        sb.append("Here is the user's latest message:\n").append(message)
          .append("\n\nPlease generate the corresponding entities and relationships strictly in JSON format matching the given schema.");
        return sb.toString();
    }

    /**
     * 把 @ mentions 渲染成 prompt 顶部的"用户重点引用"块。
     * <ul>
     *   <li>graph: 一句话告诉 LLM "用户在指当前图谱整体"，便于其理解上下文范围;</li>
     *   <li>datasource: 一句话告诉 LLM "用户指定了下面这些库"(完整 schema 已在 dbSchemas 段);</li>
     *   <li>node: 列出 id + label + 节点 type,作为定向编辑/补全的目标;</li>
     *   <li>relation: 列出"from -> to (label)",让 LLM 围绕该关系扩展。</li>
     * </ul>
     */
    private String renderMentions(List<MentionRef> mentions,
                                  List<Map<String, Object>> nodes,
                                  List<Map<String, Object>> edges) {
        if (mentions == null || mentions.isEmpty()) return "";
        List<MentionRef> graphs = new ArrayList<>();
        List<MentionRef> dss = new ArrayList<>();
        List<MentionRef> ns = new ArrayList<>();
        List<MentionRef> es = new ArrayList<>();
        for (MentionRef m : mentions) {
            if (m == null || m.getKind() == null) continue;
            switch (m.getKind().toLowerCase()) {
                case "graph"      -> graphs.add(m);
                case "datasource" -> dss.add(m);
                case "node"       -> ns.add(m);
                case "relation"   -> es.add(m);
                default -> {}
            }
        }
        if (graphs.isEmpty() && dss.isEmpty() && ns.isEmpty() && es.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【用户本轮通过 @ 重点引用的对象 — 请把分析与产出聚焦在它们上】\n");
        if (!graphs.isEmpty()) {
            sb.append("- 当前图谱整体: ");
            for (int i = 0; i < graphs.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(graphs.get(i).getLabel());
            }
            sb.append("（请将本轮新增的实体/关系合理嵌入到该图谱中）\n");
        }
        if (!dss.isEmpty()) {
            sb.append("- 指定数据源: ");
            for (int i = 0; i < dss.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(dss.get(i).getLabel());
            }
            sb.append("（仅以下方提供的这些库的 schema 为依据,不要参考其它数据源）\n");
        }
        if (!ns.isEmpty()) {
            sb.append("- 锚点节点 (围绕这些节点扩展/编辑):\n");
            for (MentionRef m : ns) {
                Map<String, Object> hit = findNodeById(nodes, m.getId());
                String type = hit == null ? "" : String.valueOf(hit.getOrDefault("type", ""));
                sb.append("    · ").append(m.getId())
                  .append(" | ").append(m.getLabel());
                if (!type.isBlank()) sb.append(" | ").append(type);
                sb.append("\n");
            }
            sb.append("  (在 add_edges 中引用它们时请直接复用上述 id,不要造同名新节点)\n");
        }
        if (!es.isEmpty()) {
            sb.append("- 锚点关系 (围绕这些边扩展/解释):\n");
            for (MentionRef m : es) {
                Map<String, Object> e = findEdgeById(edges, m.getId());
                if (e == null) {
                    sb.append("    · ").append(m.getId()).append(" | ").append(m.getLabel()).append("\n");
                } else {
                    String f = String.valueOf(e.get("from"));
                    String t = String.valueOf(e.get("to"));
                    String fLabel = labelOfNode(nodes, f, f);
                    String tLabel = labelOfNode(nodes, t, t);
                    sb.append("    · ").append(fLabel).append(" → ").append(tLabel)
                      .append(" (").append(m.getLabel()).append(")\n");
                }
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    /** 从 mentions 中收集可作为子图截断 anchor 的节点 id（节点直接用 id;关系展开为两端节点 id）。 */
    private List<String> collectAnchorNodeIds(List<MentionRef> mentions,
                                              List<Map<String, Object>> edges) {
        if (mentions == null || mentions.isEmpty()) return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (MentionRef m : mentions) {
            if (m == null || m.getKind() == null || m.getId() == null) continue;
            if ("node".equalsIgnoreCase(m.getKind())) {
                ids.add(m.getId());
            } else if ("relation".equalsIgnoreCase(m.getKind())) {
                Map<String, Object> e = findEdgeById(edges, m.getId());
                if (e != null) {
                    Object f = e.get("from");
                    Object t = e.get("to");
                    if (f != null) ids.add(String.valueOf(f));
                    if (t != null) ids.add(String.valueOf(t));
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private static Map<String, Object> findNodeById(List<Map<String, Object>> nodes, String id) {
        if (nodes == null || id == null) return null;
        for (Map<String, Object> n : nodes) {
            if (id.equals(String.valueOf(n.get("id")))) return n;
        }
        return null;
    }

    private static Map<String, Object> findEdgeById(List<Map<String, Object>> edges, String id) {
        if (edges == null || id == null) return null;
        for (Map<String, Object> e : edges) {
            if (id.equals(String.valueOf(e.get("id")))) return e;
        }
        return null;
    }

    private static String labelOfNode(List<Map<String, Object>> nodes, String id, String fallback) {
        Map<String, Object> hit = findNodeById(nodes, id);
        if (hit == null) return fallback;
        Object lb = hit.get("label");
        return lb == null ? fallback : String.valueOf(lb);
    }

    /**
     * 构造推演用的完整 prompt（system + user）。
     * <p>纯函数，不发起网络调用；orchestrator 把 user 部分原样写入 Scenario.rawPrompt 供前端展示。
     */
    public PredictPromptArtifact buildPredictPrompt(PredictRequest req) {
        int steps = req.getSteps() == null ? 4 : Math.max(1, Math.min(10, req.getSteps()));
        boolean backward = "backward".equalsIgnoreCase(req.getIntent());

        String systemPrompt = backward ? LlmPrompts.PREDICT_BACKWARD_SYSTEM : LlmPrompts.PREDICT_SYSTEM;
        String seedRole = backward ? "目标节点 (seeds，需要溯因的结果)" : "起点节点 (seeds)";
        String taskWord = backward ? "请向上回溯 " : "请向前推演 ";
        String taskUnit = backward ? " 层上游原因" : " 步";

        String graphSummary = summarizeGraph(req.getNodes(), req.getEdges());
        String seedSummary = summarizeSeeds(req.getSeeds(), req.getNodes());
        String rulesSummary = summarizeRules(req.getNodes());
        String constraintsSummary = summarizeConstraints(req.getConstraints(), req.getNodes());

        TruncatedGraph truncated = truncateGraphForContext(
                req.getNodes(), req.getEdges(), req.getSeeds(), req.getConstraints());
        boolean wasTruncated = truncated.droppedNodes() > 0 || truncated.droppedEdges() > 0;
        if (wasTruncated) {
            graphSummary = summarizeGraph(truncated.nodes(), truncated.edges());
        }

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("当前本体图谱：\n").append(graphSummary).append("\n");
        if (wasTruncated) {
            userPrompt.append("(为控制 LLM context 已截断 ")
                      .append(truncated.droppedNodes()).append(" 个节点 / ")
                      .append(truncated.droppedEdges()).append(" 条边，仅保留 seeds、规则、约束目标及其 ")
                      .append(CONTEXT_HOPS).append("-hop 邻域。)\n");
        }
        userPrompt.append("\n");
        if (!rulesSummary.isBlank()) {
            userPrompt.append("可用规则 (优先沿规则推演)：\n").append(rulesSummary).append("\n");
        }
        userPrompt.append(seedRole).append("：\n").append(seedSummary).append("\n");
        if (!constraintsSummary.isBlank()) {
            userPrompt.append("\nWhat-if 约束（必须严格遵守）：\n").append(constraintsSummary).append("\n");
        }
        if (req.getPrompt() != null && !req.getPrompt().isBlank()) {
            userPrompt.append("\n额外场景说明：").append(req.getPrompt()).append("\n");
        }
        userPrompt.append("\n").append(taskWord).append(steps).append(taskUnit).append("，严格按 schema 输出 JSON。");

        return new PredictPromptArtifact(
                systemPrompt,
                userPrompt.toString(),
                wasTruncated,
                truncated.droppedNodes(),
                truncated.droppedEdges());
    }

    /** 把图谱压缩成 LLM 能读的可读文本（ASCII 表格风），节点 / 边各一段。 */
    public String summarizeGraph(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        StringBuilder sb = new StringBuilder();
        if (nodes != null) {
            sb.append("节点 (id | label | type):\n");
            for (Map<String, Object> n : nodes) {
                sb.append("  ").append(n.get("id"))
                  .append(" | ").append(n.get("label"))
                  .append(" | ").append(n.get("type"))
                  .append("\n");
            }
        }
        if (edges != null && !edges.isEmpty()) {
            sb.append("边 (from -> to : label, rule_driven):\n");
            for (Map<String, Object> e : edges) {
                Object rd = e.get("rule_driven");
                sb.append("  ").append(e.get("from"))
                  .append(" -> ").append(e.get("to"))
                  .append(" : ").append(e.getOrDefault("label", ""))
                  .append(Boolean.TRUE.equals(rd) ? " [rule]" : "")
                  .append("\n");
            }
        }
        return sb.toString();
    }

    /** 提取规则节点（type=rule）的可读摘要，单独列出方便 LLM 在推演时优先用规则。 */
    public String summarizeRules(List<Map<String, Object>> nodes) {
        if (nodes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> n : nodes) {
            if (!"rule".equalsIgnoreCase(String.valueOf(n.get("type")))) continue;
            sb.append("  - ").append(n.get("id"))
              .append(" | ").append(n.get("label"));
            Object props = n.get("properties");
            if (props instanceof Map<?, ?> p) {
                Object br = p.get("baseRate");
                Object w = p.get("weight");
                if (br != null) sb.append(" | baseRate=").append(br);
                if (w != null) sb.append(" | weight=").append(w);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 把 what-if 约束转成中文 prompt 文本：block / probability / force 三种模式各对应一段说明。
     * <p>probability 模式提示 LLM 把先验作为初值做贝叶斯更新。
     */
    public String summarizeConstraints(List<Constraint> cs, List<Map<String, Object>> nodes) {
        if (cs == null || cs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Constraint c : cs) {
            if (c == null || c.getNodeId() == null) continue;
            String label = c.getNodeId();
            if (nodes != null) {
                for (Map<String, Object> n : nodes) {
                    if (c.getNodeId().equals(n.get("id"))) {
                        Object lb = n.get("label");
                        if (lb != null) label = c.getNodeId() + "(" + lb + ")";
                        break;
                    }
                }
            }
            if ("block".equalsIgnoreCase(c.getMode())) {
                sb.append("  - 禁止: ").append(label)
                  .append(" 不发生；预测中不得以其为 triggered_by / leads_to，也不得预测出等价节点。\n");
            } else if ("probability".equalsIgnoreCase(c.getMode())) {
                double p = c.getProbability() == null ? 0.5 : Math.max(0.0, Math.min(1.0, c.getProbability()));
                sb.append("  - 概率: ").append(label)
                  .append(" 先验概率 = ").append(String.format("%.2f", p))
                  .append("。请把先验作为初值，结合上下游证据用贝叶斯式更新；返回的 confidence 应反映综合后验。\n");
            } else {
                sb.append("  - 强制: ").append(label)
                  .append(" 必然发生，可作为 step=1 的合法上游/下游连接点。\n");
            }
            if (c.getNote() != null && !c.getNote().isBlank()) {
                sb.append("    说明: ").append(c.getNote()).append("\n");
            }
        }
        return sb.toString();
    }

    /** 把 seeds 列成 "id (label)" 格式，让 LLM 在 prompt 中直接看到种子的人类可读名称。 */
    public String summarizeSeeds(List<String> seeds, List<Map<String, Object>> nodes) {
        if (seeds == null || seeds.isEmpty()) return "(未指定，请基于全图任选一个合理起点)";
        StringBuilder sb = new StringBuilder();
        for (String s : seeds) {
            sb.append("  - ").append(s);
            if (nodes != null) {
                for (Map<String, Object> n : nodes) {
                    if (s.equals(n.get("id"))) {
                        sb.append(" (").append(n.get("label")).append(")");
                        break;
                    }
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 根据预算把大图谱裁剪成"以 seeds / rules / constraints 为中心的 N-hop 邻域"。
     * <p>策略：
     * <ol>
     *   <li>未超预算直接返回原图；</li>
     *   <li>把 seeds、所有 rule 节点、constraints 目标作为 mandatory；</li>
     *   <li>从 mandatory 出发 BFS 向外扩 {@link #CONTEXT_HOPS} 层，按发现顺序加入直到达到节点 budget；</li>
     *   <li>边只保留两端都在 keep 集合里的，不超过边 budget。</li>
     * </ol>
     */
    public TruncatedGraph truncateGraphForContext(List<Map<String, Object>> nodes,
                                                  List<Map<String, Object>> edges,
                                                  List<String> seeds,
                                                  List<Constraint> constraints) {
        int totalNodes = nodes == null ? 0 : nodes.size();
        int totalEdges = edges == null ? 0 : edges.size();
        if (totalNodes <= CONTEXT_NODE_BUDGET && totalEdges <= CONTEXT_EDGE_BUDGET) {
            return new TruncatedGraph(
                    nodes == null ? new ArrayList<>() : nodes,
                    edges == null ? new ArrayList<>() : edges,
                    0, 0);
        }

        Set<String> mandatory = new LinkedHashSet<>();
        if (seeds != null) mandatory.addAll(seeds);
        if (nodes != null) {
            for (Map<String, Object> n : nodes) {
                if ("rule".equalsIgnoreCase(String.valueOf(n.get("type")))) {
                    mandatory.add(String.valueOf(n.get("id")));
                }
            }
        }
        if (constraints != null) {
            for (Constraint c : constraints) {
                if (c != null && c.getNodeId() != null) mandatory.add(c.getNodeId());
            }
        }

        Set<String> keep = new LinkedHashSet<>(mandatory);

        Map<String, List<String>> neighbors = new HashMap<>();
        if (edges != null) {
            for (Map<String, Object> e : edges) {
                String f = String.valueOf(e.get("from"));
                String t = String.valueOf(e.get("to"));
                neighbors.computeIfAbsent(f, k -> new ArrayList<>()).add(t);
                neighbors.computeIfAbsent(t, k -> new ArrayList<>()).add(f);
            }
        }

        Set<String> frontier = new HashSet<>(mandatory);
        for (int hop = 0; hop < CONTEXT_HOPS && keep.size() < CONTEXT_NODE_BUDGET; hop++) {
            Set<String> next = new LinkedHashSet<>();
            for (String id : frontier) {
                List<String> nbs = neighbors.get(id);
                if (nbs != null) for (String nb : nbs) if (!keep.contains(nb)) next.add(nb);
            }
            for (String nb : next) {
                if (keep.size() >= CONTEXT_NODE_BUDGET) break;
                keep.add(nb);
            }
            if (next.isEmpty()) break;
            frontier = next;
        }

        List<Map<String, Object>> outNodes = new ArrayList<>();
        if (nodes != null) {
            for (Map<String, Object> n : nodes) {
                if (keep.contains(String.valueOf(n.get("id")))) outNodes.add(n);
            }
        }
        List<Map<String, Object>> outEdges = new ArrayList<>();
        if (edges != null) {
            for (Map<String, Object> e : edges) {
                if (keep.contains(String.valueOf(e.get("from")))
                        && keep.contains(String.valueOf(e.get("to")))) {
                    outEdges.add(e);
                    if (outEdges.size() >= CONTEXT_EDGE_BUDGET) break;
                }
            }
        }
        return new TruncatedGraph(outNodes, outEdges,
                totalNodes - outNodes.size(),
                totalEdges - outEdges.size());
    }

    // ============================================================
    // 数据库 schema 序列化（给 chat / extract 两种场景用）
    // ============================================================

    // chat 上下文里嵌入 schema 时的预算：列详情每张表只挑前 N 列，避免炸 context
    private static final int CHAT_SCHEMA_COLS_PER_TABLE = 12;
    private static final int CHAT_SCHEMA_TABLES_MAX = 60;

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

    // ============================================================
    // 数据库 schema → 本体血缘图：专用 prompt 构造
    // ============================================================

    /** schema → ontology user prompt 产物。 */
    public record SchemaExtractPrompt(String system, String user) {}

    /**
     * 构造"DB schema → ontology lineage"的完整 prompt。
     * <p>系统 prompt 用 {@link LlmPrompts#SCHEMA_TO_ONTOLOGY_SYSTEM}，user 部分把 schema 全量
     * 详尽展开（不像 chat 场景那样省略列），让 LLM 拿到最完整的"事实"。
     */
    public SchemaExtractPrompt buildSchemaExtractPrompt(DatabaseSchemaInfo schema,
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
        sb.append("  - 每张表 → 1 个节点（type 按系统规则分类）；\n");
        sb.append("  - 每个外键 → 1 条边（rel_type 按系统规则选择）；\n");
        sb.append("  - attributes 只能列出 SCHEMA 中真实出现的列（H2 反幻觉规则）；\n");
        sb.append("  - constraints 只能基于真实 PK / 唯一键 / NOT NULL / 声明的 FK（H5）；\n");
        sb.append("  - 节点 id 用 `t_<table>`，边 id 用 `e_fk_<child>_<col>__<parent>`，保证幂等；\n");
        sb.append("  - 不要输出 `question` 字段；\n");
        sb.append("  - 不允许出现 add_nodes 之外的 from/to 引用（无悬空边）；\n");
        sb.append("  - ⚠ 服务端会做事实校验：编造的表/列/FK 会被自动删除。宁可少写也不要多写。\n");
        return new SchemaExtractPrompt(LlmPrompts.SCHEMA_TO_ONTOLOGY_SYSTEM, sb.toString());
    }

    /** Full 版 schema 渲染：extract 场景用，每张表完整列出所有列 + 全部约束 + 全部外键。 */
    public String renderSchemaFull(DatabaseSchemaInfo s) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据库类型: ").append(s.kind()).append("\n");
        sb.append("数据库名:   ").append(s.database()).append("\n");
        sb.append("表数量:     ").append(s.tables().size()).append("\n\n");
        for (TableInfo t : s.tables()) {
            sb.append("TABLE ").append(t.name());
            if (t.comment() != null && !t.comment().isBlank()) {
                sb.append("    -- ").append(t.comment());
            }
            if (t.estimatedRows() != null && t.estimatedRows() > 0) {
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
            sb.append("\n");
        }
        return sb.toString();
    }
}

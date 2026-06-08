package com.tuiyan.backend.service.llm;

import com.tuiyan.backend.model.MentionRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * chat 场景的 user prompt 拼接：把现有图谱摘要 / RAG / DB schema / @ mentions 组合成上下文。
 */
@Component
public class ChatPromptBuilder {

    private final GraphSummarizer graphSummarizer;
    private final SchemaPromptRenderer schemaPromptRenderer;

    public ChatPromptBuilder(GraphSummarizer graphSummarizer, SchemaPromptRenderer schemaPromptRenderer) {
        this.graphSummarizer = graphSummarizer;
        this.schemaPromptRenderer = schemaPromptRenderer;
    }

    /** chat 用 user prompt（含 RAG + 数据库 schema + @ mentions）。 */
    public String buildChatPrompt(List<Map<String, Object>> nodes,
                                  List<Map<String, Object>> edges,
                                  String message,
                                  List<GraphPromptBuilder.RagChunk> ragChunks,
                                  List<GraphPromptBuilder.DbSchema> dbSchemas,
                                  List<MentionRef> mentions) {
        StringBuilder sb = new StringBuilder();

        // ===== @ 引用块: 让 LLM 在最显眼的位置看到"用户重点关注"的对象 =====
        // 注意: 数据源 / 经验文件类型的 mention 在这里只列名（其完整 schema / 正文分别在 dbSchemas、
        // ragChunks 段给出）,节点/关系/图谱则让 LLM 把焦点聚到这些已存在的对象上而非另起炉灶。
        String mentionBlock = renderMentions(mentions, nodes, edges);
        if (!mentionBlock.isEmpty()) {
            sb.append(mentionBlock);
        }

        // 数据库 schema 上下文(放在最前面,作为强结构化输入提示)
        if (dbSchemas != null && !dbSchemas.isEmpty()) {
            sb.append("可参考的数据库表结构 (来自当前工作空间已接入的数据源，请据此设计实体/关系):\n");
            for (GraphPromptBuilder.DbSchema s : dbSchemas) {
                sb.append("- 数据源「").append(s.sourceName()).append("」 (")
                  .append(s.kind()).append(", 库: ").append(s.database()).append("):\n");
                // 优先用结构化详情；没有就退回到只有表名
                if (s.detail() != null && s.detail().tables() != null
                        && !s.detail().tables().isEmpty()) {
                    sb.append(schemaPromptRenderer.renderSchemaCompact(s.detail()));
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
            for (GraphPromptBuilder.RagChunk chunk : ragChunks) {
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
            if (!anchorIds.isEmpty() && (totalNodes > GraphSummarizer.CONTEXT_NODE_BUDGET / 2
                    || totalEdges > GraphSummarizer.CONTEXT_EDGE_BUDGET / 2)) {
                GraphPromptBuilder.TruncatedGraph tg = graphSummarizer.truncateGraphForContext(nodes, edges, anchorIds, null);
                if (tg.droppedNodes() > 0 || tg.droppedEdges() > 0) {
                    nodesForPrompt = tg.nodes();
                    edgesForPrompt = tg.edges();
                    focused = true;
                    sb.append("(已根据 @ 引用聚焦到 ").append(anchorIds.size())
                      .append(" 个锚点节点的 ").append(GraphSummarizer.CONTEXT_HOPS).append("-hop 邻域: 保留 ")
                      .append(nodesForPrompt.size()).append(" / ").append(totalNodes).append(" 节点 + ")
                      .append(edgesForPrompt.size()).append(" / ").append(totalEdges).append(" 边)\n\n");
                }
            }
            sb.append("Existing ontology graph (the user is incrementally extending this — do NOT recreate any of these; reuse the ids exactly when you need to reference them):\n");
            sb.append(graphSummarizer.summarizeGraph(nodesForPrompt, edgesForPrompt));
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
        List<MentionRef> exps = new ArrayList<>();
        List<MentionRef> ns = new ArrayList<>();
        List<MentionRef> es = new ArrayList<>();
        for (MentionRef m : mentions) {
            if (m == null || m.getKind() == null) continue;
            switch (m.getKind().toLowerCase()) {
                case "graph"      -> graphs.add(m);
                case "datasource" -> dss.add(m);
                case "experience" -> exps.add(m);
                case "node"       -> ns.add(m);
                case "relation"   -> es.add(m);
                default -> {}
            }
        }
        if (graphs.isEmpty() && dss.isEmpty() && exps.isEmpty() && ns.isEmpty() && es.isEmpty()) return "";

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
        if (!exps.isEmpty()) {
            sb.append("- 指定经验文件: ");
            for (int i = 0; i < exps.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(exps.get(i).getLabel());
            }
            sb.append("（其完整正文已在下方「相关数据源内容」中以「经验(@指定)：」开头给出,请优先据此作答）\n");
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
}

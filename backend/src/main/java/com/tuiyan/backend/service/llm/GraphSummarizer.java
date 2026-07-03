package com.tuiyan.backend.service.llm;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱摘要与上下文截断：把图谱压成 LLM 可读文本、按预算裁剪成 N-hop 邻域。
 * <p>供 {@link ChatPromptBuilder} 使用。
 */
@Component
public class GraphSummarizer {

    // 单次给 LLM 的图谱预算
    static final int CONTEXT_NODE_BUDGET = 120;
    static final int CONTEXT_EDGE_BUDGET = 240;
    // 从 mandatory 节点向外扩散的跳数；3 跳通常足够覆盖核心因果链
    static final int CONTEXT_HOPS = 3;

    /**
     * 把图谱压缩成 LLM 能读的可读文本（ASCII 表格风），节点 / 边各一段。
     * <p>边行必须带 <b>edge id</b>：chat 编辑协议要求 LLM 在 remove_edges / update_edges 里
     * 原样回填现有边的 id——摘要里不给 id，删边/改边就只能靠 LLM 编造、必然被防护过滤掉。
     * rel_type 同理：不展示的话 LLM 既看不懂现有边语义，也无法按协议修改它。
     */
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
            sb.append("边 (edge_id | from -> to : label (rel_type), rule_driven):\n");
            for (Map<String, Object> e : edges) {
                Object rd = e.get("rule_driven");
                Object relType = e.get("rel_type");
                sb.append("  ").append(e.get("id"))
                  .append(" | ").append(e.get("from"))
                  .append(" -> ").append(e.get("to"))
                  .append(" : ").append(e.getOrDefault("label", ""));
                if (relType != null && !String.valueOf(relType).isBlank()) {
                    sb.append(" (").append(relType).append(")");
                }
                sb.append(Boolean.TRUE.equals(rd) ? " [rule]" : "")
                  .append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 无 @ 锚点时的自动种子：按「度数（连接的边数）从高到低」选出图的核心节点。
     * <p>度数高的节点是图的枢纽，以它们为中心裁剪出的邻域最能代表整图结构；
     * rule 节点无需在此选入——{@link #truncateGraphForContext} 会把它们强制并入 mandatory。
     * 同度数时保持 nodes 原序（稳定输出，同一图两次调用产出一致的上下文）。
     */
    public List<String> autoSeeds(List<Map<String, Object>> nodes,
                                  List<Map<String, Object>> edges,
                                  int limit) {
        if (nodes == null || nodes.isEmpty() || limit <= 0) return List.of();
        Map<String, Integer> degree = new HashMap<>();
        if (edges != null) {
            for (Map<String, Object> e : edges) {
                String f = String.valueOf(e.get("from"));
                String t = String.valueOf(e.get("to"));
                degree.merge(f, 1, Integer::sum);
                degree.merge(t, 1, Integer::sum);
            }
        }
        List<String> ids = new ArrayList<>(nodes.size());
        for (Map<String, Object> n : nodes) {
            Object id = n.get("id");
            if (id != null) ids.add(String.valueOf(id));
        }
        ids.sort((a, b) -> Integer.compare(degree.getOrDefault(b, 0), degree.getOrDefault(a, 0)));
        return ids.size() > limit ? new ArrayList<>(ids.subList(0, limit)) : ids;
    }

    /**
     * 根据预算把大图谱裁剪成"以 seeds / rules 为中心的 N-hop 邻域"。
     * <p>策略：
     * <ol>
     *   <li>未超预算直接返回原图；</li>
     *   <li>把 seeds、所有 rule 节点作为 mandatory；</li>
     *   <li>从 mandatory 出发 BFS 向外扩 {@link #CONTEXT_HOPS} 层，按发现顺序加入直到达到节点 budget；</li>
     *   <li>边只保留两端都在 keep 集合里的，不超过边 budget。</li>
     * </ol>
     */
    public GraphPromptBuilder.TruncatedGraph truncateGraphForContext(List<Map<String, Object>> nodes,
                                                                     List<Map<String, Object>> edges,
                                                                     List<String> seeds) {
        int totalNodes = nodes == null ? 0 : nodes.size();
        int totalEdges = edges == null ? 0 : edges.size();
        if (totalNodes <= CONTEXT_NODE_BUDGET && totalEdges <= CONTEXT_EDGE_BUDGET) {
            return new GraphPromptBuilder.TruncatedGraph(
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
        return new GraphPromptBuilder.TruncatedGraph(outNodes, outEdges,
                totalNodes - outNodes.size(),
                totalEdges - outEdges.size());
    }
}

package com.tuiyan.backend.service;

import com.tuiyan.backend.exception.ResourceNotFoundException;
import com.tuiyan.backend.repository.OntologyModelRepository;
import com.tuiyan.backend.support.EdgeSemantics;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 大图服务端查询层（面向千张/万张表的模型）：不把整图拉到前端渲染，而是在服务端算出
 * <b>聚焦子图</b>（某节点 N 跳邻域）、<b>领域汇总</b>（把上万表节点聚成几十个领域超级节点）与
 * <b>规模摘要</b>（节点/边/领域计数）。前端据此判定「大图模式」并只渲染可控的子集。
 * <p>血缘方向语义复用 {@link EdgeSemantics}，与前端 {@code useLineageTrace} 一致。
 */
@Service
public class GraphQueryService {

    /** 子图/入口枢纽默认节点上限（前端渲染安全阈值）。 */
    public static final int DEFAULT_SUBGRAPH_LIMIT = 300;
    public static final int MAX_SUBGRAPH_LIMIT = 1500;

    private final OntologyModelRepository modelRepo;

    public GraphQueryService(OntologyModelRepository modelRepo) {
        this.modelRepo = modelRepo;
    }

    public enum Dir { UP, DOWN, BOTH }

    /** 归属校验后加载图（不属于当前工作空间 → 404），防跨空间读大图。 */
    private OntologyModelRepository.NodesAndEdges requireGraph(String modelId) {
        if (!modelRepo.existsInWorkspace(modelId)) {
            throw new ResourceNotFoundException("模型不存在或不属于当前工作空间：" + modelId);
        }
        return modelRepo.loadGraphForVersion(modelId);
    }

    /** 规模摘要：{nodeCount, edgeCount, domainCount}。 */
    public Map<String, Object> summary(String modelId) {
        OntologyModelRepository.NodesAndEdges g = requireGraph(modelId);
        return summaryOf(g.nodes(), g.edges());
    }

    static Map<String, Object> summaryOf(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Set<String> domains = new HashSet<>();
        for (Map<String, Object> n : nodes) {
            String d = str(n.get("domain"));
            if (!d.isBlank()) domains.add(d);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeCount", nodes.size());
        out.put("edgeCount", edges.size());
        out.put("domainCount", domains.size());
        return out;
    }

    /**
     * 聚焦子图：从 {@code seedId} 出发按血缘方向 BFS 取 {@code depth} 跳邻域（节点数封顶 {@code limit}）。
     * {@code seedId} 为空时退化为「入口枢纽」——按度数最高的前 {@code limit} 个节点及其内部边，供无起点时切入。
     * @return {nodes:[完整节点], edges:[两端都在子集内的边], truncated:bool, seed:id}
     */
    public Map<String, Object> subgraph(String modelId, String seedId, int depth, Dir dir, int limit) {
        OntologyModelRepository.NodesAndEdges g = requireGraph(modelId);
        return subgraphOf(g.nodes(), g.edges(), seedId, depth, dir, limit);
    }

    static Map<String, Object> subgraphOf(List<Map<String, Object>> nodes, List<Map<String, Object>> edges,
                                          String seedId, int depth, Dir dir, int limit) {
        int cap = limit <= 0 ? DEFAULT_SUBGRAPH_LIMIT : Math.min(limit, MAX_SUBGRAPH_LIMIT);
        int maxDepth = depth <= 0 ? 2 : depth;
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> n : nodes) {
            String id = str(n.get("id"));
            if (!id.isEmpty()) byId.put(id, n);
        }

        Set<String> keep = new LinkedHashSet<>();
        boolean truncated;
        if (seedId == null || seedId.isBlank() || !byId.containsKey(seedId)) {
            // 无有效起点：取度数最高的前 cap 个节点作入口枢纽
            Map<String, Integer> degree = new HashMap<>();
            for (Map<String, Object> e : edges) {
                degree.merge(str(e.get("from")), 1, Integer::sum);
                degree.merge(str(e.get("to")), 1, Integer::sum);
            }
            List<String> ordered = new ArrayList<>(byId.keySet());
            ordered.sort((a, b) -> Integer.compare(degree.getOrDefault(b, 0), degree.getOrDefault(a, 0)));
            truncated = ordered.size() > cap;
            for (int i = 0; i < Math.min(cap, ordered.size()); i++) keep.add(ordered.get(i));
        } else {
            // 邻域 BFS：按 rel_type 归一化流向建邻接，按方向取上/下游/双向
            Map<String, List<String>> fwd = new HashMap<>();  // 上游源 → 下游
            Map<String, List<String>> bwd = new HashMap<>();  // 下游 → 上游源
            for (Map<String, Object> e : edges) {
                String f = str(e.get("from")), t = str(e.get("to")), rel = str(e.get("rel_type"));
                if (f.isEmpty() || t.isEmpty() || f.equals(t) || EdgeSemantics.nonLineage(rel)) continue;
                String[] st = EdgeSemantics.sourceTarget(f, t, rel);   // [源, 目标]
                fwd.computeIfAbsent(st[0], k -> new ArrayList<>()).add(st[1]);
                bwd.computeIfAbsent(st[1], k -> new ArrayList<>()).add(st[0]);
            }
            keep.add(seedId);
            Deque<String> frontier = new ArrayDeque<>();
            frontier.add(seedId);
            truncated = false;
            for (int d = 0; d < maxDepth && !frontier.isEmpty(); d++) {
                int layerSize = frontier.size();
                for (int i = 0; i < layerSize; i++) {
                    String cur = frontier.poll();
                    List<String> nbrs = new ArrayList<>();
                    if (dir == Dir.DOWN || dir == Dir.BOTH) nbrs.addAll(fwd.getOrDefault(cur, List.of()));
                    if (dir == Dir.UP || dir == Dir.BOTH) nbrs.addAll(bwd.getOrDefault(cur, List.of()));
                    for (String nx : nbrs) {
                        if (keep.contains(nx)) continue;
                        if (keep.size() >= cap) { truncated = true; break; }
                        keep.add(nx);
                        frontier.add(nx);
                    }
                    if (keep.size() >= cap) { truncated = true; break; }
                }
                if (truncated) break;
            }
        }

        List<Map<String, Object>> outNodes = new ArrayList<>(keep.size());
        for (String id : keep) outNodes.add(byId.get(id));
        List<Map<String, Object>> outEdges = new ArrayList<>();
        for (Map<String, Object> e : edges) {
            if (keep.contains(str(e.get("from"))) && keep.contains(str(e.get("to")))) outEdges.add(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", outNodes);
        out.put("edges", outEdges);
        out.put("truncated", truncated);
        out.put("seed", seedId == null ? "" : seedId);
        return out;
    }

    /**
     * 领域汇总：把节点按 {@code domain} 聚成超级节点（含计数），并统计跨领域的血缘流向条数。
     * @return {domains:[{domain, nodeCount}], edges:[{from, to, count}]}（from/to 为领域名，按流向）
     */
    public Map<String, Object> domainRollup(String modelId) {
        OntologyModelRepository.NodesAndEdges g = requireGraph(modelId);
        return domainRollupOf(g.nodes(), g.edges());
    }

    static Map<String, Object> domainRollupOf(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, String> domainOf = new HashMap<>();
        Map<String, Integer> domainCount = new LinkedHashMap<>();
        for (Map<String, Object> n : nodes) {
            String id = str(n.get("id"));
            String d = str(n.get("domain"));
            if (d.isBlank()) d = "未分域";
            if (!id.isEmpty()) domainOf.put(id, d);
            domainCount.merge(d, 1, Integer::sum);
        }
        // 跨领域血缘流向计数（按归一化方向）
        Map<String, Integer> interEdge = new LinkedHashMap<>();
        for (Map<String, Object> e : edges) {
            String f = str(e.get("from")), t = str(e.get("to")), rel = str(e.get("rel_type"));
            if (f.isEmpty() || t.isEmpty() || f.equals(t) || EdgeSemantics.nonLineage(rel)) continue;
            String[] st = EdgeSemantics.sourceTarget(f, t, rel);
            String sd = domainOf.getOrDefault(st[0], "未分域"), rd = domainOf.getOrDefault(st[1], "未分域");
            if (sd.equals(rd)) continue;
            interEdge.merge(sd + " " + rd, 1, Integer::sum);
        }
        List<Map<String, Object>> domains = new ArrayList<>();
        for (Map.Entry<String, Integer> en : domainCount.entrySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("domain", en.getKey());
            d.put("nodeCount", en.getValue());
            domains.add(d);
        }
        domains.sort((a, b) -> Integer.compare((int) b.get("nodeCount"), (int) a.get("nodeCount")));
        List<Map<String, Object>> outEdges = new ArrayList<>();
        for (Map.Entry<String, Integer> en : interEdge.entrySet()) {
            String[] pair = en.getKey().split(" ", 2);
            Map<String, Object> ed = new LinkedHashMap<>();
            ed.put("from", pair[0]);
            ed.put("to", pair.length > 1 ? pair[1] : "");
            ed.put("count", en.getValue());
            outEdges.add(ed);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domains", domains);
        out.put("edges", outEdges);
        return out;
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
}

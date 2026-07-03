package com.tuiyan.backend.service;

import com.tuiyan.backend.repository.OntologyModelRepository;
import com.tuiyan.backend.support.EdgeSemantics;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 血缘上下游遍历：从某节点出发，按 {@link EdgeSemantics} 解析的 rel_type 数据流方向做 BFS，
 * 而不是死按边的存储方向 from→to。
 * <ul>
 *   <li>{@code DOWNSTREAM}：沿「上游源 → 下游」方向，找出该节点的所有下游（被它派生 / 影响的节点）；</li>
 *   <li>{@code UPSTREAM}：反向，找出该节点的所有上游来源。</li>
 * </ul>
 */
@Service
public class LineageTraversalService {

    public enum Direction { UPSTREAM, DOWNSTREAM }

    private final OntologyModelRepository repo;

    public LineageTraversalService(OntologyModelRepository repo) {
        this.repo = repo;
    }

    /**
     * @param modelId             本体模型 id
     * @param nodeId              起点节点 id
     * @param dir                 上游 / 下游
     * @param maxDepth            最大跳数（≤0 表示不限）
     * @param includeAssociations true 时把 {@code associated_with} 等无方向的纯关联边也纳入遍历
     *                            （按存储方向 from→to 处理）；默认应传 false，避免关联噪声污染上下游
     * @return {root, direction, maxDepth, includeAssociations, skippedAssociations,
     *          nodes:[{id,label,depth}], edges:[经过的边(含 rel_type/evidence)]}
     */
    public Map<String, Object> traverse(String modelId, String nodeId, Direction dir, int maxDepth,
                                        boolean includeAssociations) {
        OntologyModelRepository.NodesAndEdges g = repo.loadGraphForVersion(modelId);

        Map<String, String> labelOf = new HashMap<>();
        for (Map<String, Object> n : g.nodes()) {
            String id = str(n.get("id"));
            if (id != null) labelOf.put(id, str(n.get("label")));
        }

        // 按血缘方向建邻接表：邻居 + 走过的边。
        // DOWNSTREAM 用「上游源 → 下游」；UPSTREAM 用「下游 → 上游源」。
        Map<String, List<Object[]>> adj = new HashMap<>();
        int skippedAssociations = 0;
        for (Map<String, Object> e : g.edges()) {
            String from = str(e.get("from"));
            String to = str(e.get("to"));
            if (from == null || to == null) continue;
            if (!includeAssociations && EdgeSemantics.nonLineage(str(e.get("rel_type")))) {
                skippedAssociations++; // 纯关联不构成派生关系，默认排除并报告数量
                continue;
            }
            String[] st = EdgeSemantics.sourceTarget(from, to, str(e.get("rel_type")));
            String src = st[0]; // 上游
            String tgt = st[1]; // 下游
            String key = (dir == Direction.DOWNSTREAM) ? src : tgt;
            String neighbor = (dir == Direction.DOWNSTREAM) ? tgt : src;
            adj.computeIfAbsent(key, k -> new ArrayList<>()).add(new Object[]{neighbor, e});
        }

        int limit = maxDepth <= 0 ? Integer.MAX_VALUE : maxDepth;
        Map<String, Integer> depth = new LinkedHashMap<>();
        depth.put(nodeId, 0);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(nodeId);
        List<Map<String, Object>> traversedEdges = new ArrayList<>();
        Set<String> edgeSeen = new HashSet<>();

        while (!queue.isEmpty()) {
            String cur = queue.poll();
            int d = depth.get(cur);
            if (d >= limit) continue;
            for (Object[] pair : adj.getOrDefault(cur, List.of())) {
                String nb = (String) pair[0];
                @SuppressWarnings("unchecked")
                Map<String, Object> em = (Map<String, Object>) pair[1];
                String eid = str(em.get("id"));
                if (eid == null || edgeSeen.add(eid)) traversedEdges.add(em);
                if (!depth.containsKey(nb)) {
                    depth.put(nb, d + 1);
                    queue.add(nb);
                }
            }
        }

        List<Map<String, Object>> nodesOut = new ArrayList<>();
        for (Map.Entry<String, Integer> en : depth.entrySet()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", en.getKey());
            one.put("label", labelOf.getOrDefault(en.getKey(), en.getKey()));
            one.put("depth", en.getValue());
            nodesOut.add(one);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", nodeId);
        out.put("direction", dir.name().toLowerCase());
        out.put("maxDepth", maxDepth);
        out.put("includeAssociations", includeAssociations);
        out.put("skippedAssociations", skippedAssociations);
        out.put("nodes", nodesOut);
        out.put("edges", traversedEdges);
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}

package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 抽取结果的纯函数处理协作类：负责长文本切片、chunk id 前缀重写、跨 chunk 按 label 合并去重，
 * 以及抽取后图校验。全部为无副作用的 JSON / 文本处理，与 LLM / HTTP 调用解耦。
 * <p>id 契约要点：{@link #prefixChunkIds}、{@link #mergeExtractionByLabel}、
 * {@link #knownEntitiesPreface} 三者共享同一套 id 约定（chunk 前缀策略与回灌 id 必须一致），
 * 因此放在同一个类里维护，不可拆散。
 */
@Component
public class ExtractionGraphMerger {

    private static final Logger log = LoggerFactory.getLogger(ExtractionGraphMerger.class);

    // 回灌给后续 chunk 的已知实体上限：太多会撑爆 context，取最近的若干个即可。
    private static final int CARRY_FORWARD_LIMIT = 80;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 长文本按字符上限切片，但优先在自然边界（换行 / 句号）切，避免把句子从中间切断。
     * 边界搜索范围限制在 [maxChars/2, maxChars]。
     */
    public List<String> chunkText(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        if (text.length() <= maxChars) { out.add(text); return out; }
        int n = text.length();
        int idx = 0;
        while (idx < n) {
            int end = Math.min(n, idx + maxChars);
            if (end < n) {
                int bp = text.lastIndexOf('\n', end);
                if (bp <= idx + maxChars / 2) bp = text.lastIndexOf('。', end);
                if (bp <= idx + maxChars / 2) bp = text.lastIndexOf('.', end);
                if (bp > idx + maxChars / 2) end = bp + 1;
            }
            out.add(text.substring(idx, end));
            idx = end;
        }
        return out;
    }

    /**
     * 构造“已知实体清单”前言：列出截至目前已识别节点的 id + label(+别名)，
     * 指示 LLM 在本段产生的关系里直接复用这些 id，而不是重复造节点。
     * <p>这些 id 已是合并后的稳定 id（如 c0_n1），{@link #prefixChunkIds} 不会再给它们加前缀，
     * 因此本段输出的边引用它们时能正确连上。
     */
    public String knownEntitiesPreface(JsonNode merged) {
        if (merged == null) return "";
        JsonNode nodes = merged.path("add_nodes");
        if (!nodes.isArray() || nodes.isEmpty()) return "";
        int total = nodes.size();
        int start = Math.max(0, total - CARRY_FORWARD_LIMIT);
        StringBuilder sb = new StringBuilder();
        sb.append("【已在前面段落中识别的实体】若本段内容涉及它们，请在 add_edges 的 from/to 里")
          .append("直接复用下列 id（不要重复创建同名节点）；只有本段新出现的概念才创建新节点：\n");
        for (int i = start; i < total; i++) {
            JsonNode n = nodes.get(i);
            String id = n.path("id").asText("");
            String label = n.path("label").asText("");
            if (id.isEmpty() || label.isEmpty()) continue;
            sb.append("- ").append(id).append(": ").append(label);
            JsonNode aliases = n.path("aliases");
            if (aliases.isArray() && !aliases.isEmpty()) {
                sb.append("（别名: ");
                for (int j = 0; j < aliases.size(); j++) {
                    if (j > 0) sb.append("、");
                    sb.append(aliases.get(j).asText(""));
                }
                sb.append("）");
            }
            sb.append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 给某 chunk 的所有节点 / 边 id 加 chunk 前缀（如 "c0_n1"），并把边的 from/to 同步重写。
     * <p>多 chunk 抽取时不同 chunk 内 LLM 都用 n1/n2 命名，加前缀避免合并时冲突。
     */
    public JsonNode prefixChunkIds(JsonNode part, String prefix) {
        ObjectNode out = objectMapper.createObjectNode();
        if (part.has("reply")) out.set("reply", part.get("reply"));

        Map<String, String> idMap = new HashMap<>();
        ArrayNode srcNodes = part.has("add_nodes") && part.get("add_nodes").isArray()
                ? (ArrayNode) part.get("add_nodes") : objectMapper.createArrayNode();
        ArrayNode outNodes = objectMapper.createArrayNode();
        for (JsonNode n : srcNodes) {
            ObjectNode copy = n.deepCopy();
            String oldId = copy.path("id").asText("");
            if (oldId.isEmpty()) continue;
            String newId = prefix + oldId;
            idMap.put(oldId, newId);
            copy.put("id", newId);
            outNodes.add(copy);
        }
        ArrayNode srcEdges = part.has("add_edges") && part.get("add_edges").isArray()
                ? (ArrayNode) part.get("add_edges") : objectMapper.createArrayNode();
        ArrayNode outEdges = objectMapper.createArrayNode();
        for (JsonNode e : srcEdges) {
            ObjectNode copy = e.deepCopy();
            String f = copy.path("from").asText("");
            String t = copy.path("to").asText("");
            if (idMap.containsKey(f)) copy.put("from", idMap.get(f));
            if (idMap.containsKey(t)) copy.put("to", idMap.get(t));
            String eid = copy.path("id").asText("");
            if (!eid.isEmpty()) copy.put("id", prefix + eid);
            outEdges.add(copy);
        }
        out.set("add_nodes", outNodes);
        out.set("add_edges", outEdges);
        return out;
    }

    /**
     * 跨 chunk 合并：按 normalized label(+aliases) 去重节点，把重复节点的 props 合并，
     * 并对重复 id 做 from/to 重映射。
     * <p>判等同时看 type：同名但 type 明确不同视作两个概念（如 rule「风险」与 metric「风险」），
     * 不折叠；任一方 type 为空作通配（兼容 LLM 省略 type 的输出）。
     */
    public JsonNode mergeExtractionByLabel(JsonNode a, JsonNode b) {
        ObjectNode out = objectMapper.createObjectNode();
        if (a.has("reply")) out.set("reply", a.get("reply"));

        ArrayNode outNodes = objectMapper.createArrayNode();
        ArrayNode outEdges = objectMapper.createArrayNode();
        Map<String, List<String[]>> keyIndex = new HashMap<>(); // 标准化键 → 候选 {id, 标准化 type}
        Map<String, ObjectNode> idToNode = new HashMap<>();
        Map<String, String> idRemap = new HashMap<>();

        for (JsonNode n : a.path("add_nodes")) {
            ObjectNode copy = n.deepCopy();
            outNodes.add(copy);
            String id = copy.path("id").asText("");
            if (id.isEmpty()) continue; // 空 id 不参与去重映射，避免后续同名节点被重映射到 "" 而连同边一起丢失
            String type = copy.path("type").asText("");
            for (String key : labelKeys(copy)) registerKey(keyIndex, key, type, id);
            idToNode.put(id, copy);
        }
        for (JsonNode e : a.path("add_edges")) outEdges.add(e);

        for (JsonNode n : b.path("add_nodes")) {
            String id = n.path("id").asText("");
            String type = n.path("type").asText("");
            List<String> keys = labelKeys(n);
            String matchId = null;
            for (String key : keys) {
                matchId = lookupKey(keyIndex, key, type);
                if (matchId != null) break;
            }
            if (matchId != null) {
                idRemap.put(id, matchId);
                ObjectNode existing = idToNode.get(matchId);
                if (existing != null) {
                    mergeNodeProps(existing, n);
                    mergeAliases(existing, n);
                }
            } else {
                ObjectNode copy = n.deepCopy();
                outNodes.add(copy);
                if (!id.isEmpty()) {
                    for (String key : keys) registerKey(keyIndex, key, type, id);
                    idToNode.put(id, copy);
                }
            }
        }
        for (JsonNode e : b.path("add_edges")) {
            ObjectNode copy = e.deepCopy();
            String f = copy.path("from").asText("");
            String t = copy.path("to").asText("");
            copy.put("from", idRemap.getOrDefault(f, f));
            copy.put("to", idRemap.getOrDefault(t, t));
            outEdges.add(copy);
        }
        out.set("add_nodes", outNodes);
        out.set("add_edges", outEdges);
        return out;
    }

    /**
     * 抽取后图校验：保证产出的是一张“干净”的图。
     * <ol>
     *   <li>丢弃 id 为空或 id 重复的节点（保留首个）—— 重复 id 在加盐后依旧同 id，会让前端渲染/合并错乱；</li>
     *   <li>丢弃 from/to 指向不存在节点的悬空边；</li>
     *   <li>丢弃自环（from == to）；</li>
     *   <li>按 (from,to,rel_type|label) 去重，避免同一关系被多段重复抽出。</li>
     * </ol>
     */
    public JsonNode sanitizeGraph(JsonNode graph) {
        if (graph == null || !graph.isObject()) return graph;
        ObjectNode out = (ObjectNode) graph;
        JsonNode nodes = out.path("add_nodes");
        JsonNode edges = out.path("add_edges");

        Set<String> nodeIds = new HashSet<>();
        if (nodes.isArray()) {
            ArrayNode cleanedNodes = objectMapper.createArrayNode();
            int droppedNodes = 0;
            for (JsonNode n : nodes) {
                String id = n.path("id").asText("");
                if (id.isEmpty() || !nodeIds.add(id)) { droppedNodes++; continue; }
                cleanedNodes.add(n);
            }
            if (droppedNodes > 0) {
                log.info("[LLM-extract] 图校验：丢弃 {} 个空 id / 重复 id 节点，保留 {} 个",
                        droppedNodes, cleanedNodes.size());
            }
            out.set("add_nodes", cleanedNodes);
        }
        if (!edges.isArray()) return out;

        ArrayNode cleaned = objectMapper.createArrayNode();
        Set<String> seenEdge = new HashSet<>();
        int dropped = 0;
        for (JsonNode e : edges) {
            String f = e.path("from").asText("");
            String t = e.path("to").asText("");
            if (f.isEmpty() || t.isEmpty() || !nodeIds.contains(f) || !nodeIds.contains(t)) {
                dropped++;
                continue; // 悬空边
            }
            if (f.equals(t)) { dropped++; continue; } // 自环
            String relKey = e.path("rel_type").asText(e.path("label").asText(""));
            String sig = f + "->" + t + "#" + relKey;
            if (!seenEdge.add(sig)) { dropped++; continue; } // 重复边
            cleaned.add(e);
        }
        if (dropped > 0) {
            log.info("[LLM-extract] 图校验：丢弃 {} 条非法/重复边（悬空/自环/重复），保留 {} 条",
                    dropped, cleaned.size());
        }
        out.set("add_edges", cleaned);
        return out;
    }

    /** 合并节点 props：以 a 为基准，从 b 加入 a 没有的 key（同 key 取 a 的优先）。 */
    private void mergeNodeProps(ObjectNode aNode, JsonNode bNode) {
        JsonNode aProps = aNode.path("props");
        JsonNode bProps = bNode.path("props");
        if (!bProps.isArray() || bProps.isEmpty()) return;
        ArrayNode merged;
        Set<String> seenKeys = new HashSet<>();
        if (aProps.isArray()) {
            merged = (ArrayNode) aProps;
            for (JsonNode p : aProps) {
                String k = p.path("key").asText("");
                if (!k.isEmpty()) seenKeys.add(k);
            }
        } else {
            merged = objectMapper.createArrayNode();
        }
        for (JsonNode p : bProps) {
            String k = p.path("key").asText("");
            if (k.isEmpty()) continue;
            if (seenKeys.contains(k)) continue;
            merged.add(p.deepCopy());
            seenKeys.add(k);
        }
        aNode.set("props", merged);
    }

    /** 把 (标准化键, 标准化 type) 注册进去重索引。 */
    private static void registerKey(Map<String, List<String[]>> index, String key, String type, String id) {
        if (key.isEmpty() || id.isEmpty()) return;
        index.computeIfAbsent(key, k -> new ArrayList<>()).add(new String[]{id, normalizeLabel(type)});
    }

    /**
     * 去重索引查询：同键下优先 type 精确匹配；查询方或候选方 type 为空作通配；
     * 同名但 type 明确不同 → 返回 null（不折叠）。
     */
    private static String lookupKey(Map<String, List<String[]>> index, String key, String type) {
        List<String[]> cands = index.get(key);
        if (cands == null || cands.isEmpty()) return null;
        String t = normalizeLabel(type);
        if (t.isEmpty()) return cands.get(0)[0];
        String blankHit = null;
        for (String[] c : cands) {
            if (t.equals(c[1])) return c[0];
            if (c[1].isEmpty() && blankHit == null) blankHit = c[0];
        }
        return blankHit;
    }

    /** label 标准化：trim + 小写 + 多空白合一；用于 chunk 间去重的等价判断。 */
    private static String normalizeLabel(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /** 收集一个节点用于去重的所有等价键：主 label + 所有 aliases，均做标准化。 */
    private List<String> labelKeys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        String label = normalizeLabel(node.path("label").asText(""));
        if (!label.isEmpty()) keys.add(label);
        JsonNode aliases = node.path("aliases");
        if (aliases.isArray()) {
            for (JsonNode al : aliases) {
                String k = normalizeLabel(al.asText(""));
                if (!k.isEmpty() && !keys.contains(k)) keys.add(k);
            }
        }
        return keys;
    }

    /** 合并别名：把 b 的 label 与 aliases 并入 a 的 aliases（去重，不含 a 自身的主 label）。 */
    private void mergeAliases(ObjectNode aNode, JsonNode bNode) {
        Set<String> existing = new HashSet<>();
        existing.add(normalizeLabel(aNode.path("label").asText("")));
        JsonNode aAliases = aNode.path("aliases");
        ArrayNode merged = aAliases.isArray()
                ? (ArrayNode) aAliases : objectMapper.createArrayNode();
        for (JsonNode al : merged) existing.add(normalizeLabel(al.asText("")));

        List<String> candidates = new ArrayList<>();
        candidates.add(bNode.path("label").asText(""));
        if (bNode.path("aliases").isArray()) {
            for (JsonNode al : bNode.path("aliases")) candidates.add(al.asText(""));
        }
        for (String c : candidates) {
            String norm = normalizeLabel(c);
            if (norm.isEmpty() || existing.contains(norm)) continue;
            merged.add(c);
            existing.add(norm);
        }
        if (!merged.isEmpty()) aNode.set("aliases", merged);
    }
}

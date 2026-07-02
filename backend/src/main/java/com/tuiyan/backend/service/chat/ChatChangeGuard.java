package com.tuiyan.backend.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对话建模的「局部变更防护」：LLM 的输出在应用到图谱前先过一道范围校验，
 * 保证一次对话只改它该改的那部分，而不是把整张图重画 / 误删一大片。
 * <ul>
 *   <li><b>重画回收</b>：add_nodes 里 label 与现有节点相同的一律丢弃并把其 id 重映射到现有节点
 *       （上下文被裁剪时 LLM 看不到全图，常会把已有概念再画一遍——这里把"重画"折叠成 no-op）；
 *       add_edges 端点跟随重映射，与现有边重复的（同 from/to/rel_type）也丢弃；</li>
 *   <li><b>删除预算</b>：单轮删除的节点/边数量有硬上限（绝对值 + 图规模占比取大者），
 *       超限视为疑似误删，整批拦截并提示用户改用画布多选删除或分次明确指令；
 *       引用不存在 id 的删除项直接过滤；</li>
 *   <li><b>补丁白名单</b>：update_nodes / update_edges 只允许修改白名单字段，
 *       禁止改 id、禁止改边的 from/to（改端点应显式走删 + 加）。</li>
 * </ul>
 * 全部为无副作用的 JSON 处理；拦截情况通过 {@link Guarded#notices()} 返回给调用方上报用户。
 */
public final class ChatChangeGuard {

    /** 单轮最多删除节点数 = max(绝对下限, 占比 × 图节点数)。 */
    private static final int NODE_REMOVE_FLOOR = 3;
    private static final double NODE_REMOVE_RATIO = 0.2;
    private static final int EDGE_REMOVE_FLOOR = 6;
    private static final double EDGE_REMOVE_RATIO = 0.3;

    /** 节点补丁允许修改的字段。 */
    private static final Set<String> NODE_PATCH_FIELDS = Set.of(
            "label", "type", "props", "attributes", "constraints", "aliases",
            "derived_source", "derived_database", "derived_tables", "evidence", "confidence");
    /** 边补丁允许修改的字段（不含 from/to：改端点须显式删+加）。 */
    private static final Set<String> EDGE_PATCH_FIELDS = Set.of(
            "label", "rel_type", "rule_driven", "constraints",
            "derived_source", "derived_database", "derived_tables", "evidence", "confidence");

    private ChatChangeGuard() {}

    /** 防护结果：过滤/重映射后的六组变更 + 拦截提示（空 = 未拦截任何东西）。 */
    public record Guarded(ArrayNode addNodes, ArrayNode addEdges,
                          ArrayNode removeNodes, ArrayNode removeEdges,
                          ArrayNode updateNodes, ArrayNode updateEdges,
                          List<String> notices) {}

    public static Guarded apply(List<Map<String, Object>> existingNodes,
                                List<Map<String, Object>> existingEdges,
                                JsonNode addNodes, JsonNode addEdges,
                                ArrayNode removeNodes, ArrayNode removeEdges,
                                ArrayNode updateNodes, ArrayNode updateEdges) {
        List<String> notices = new ArrayList<>();
        JsonNodeFactory f = JsonNodeFactory.instance;

        // ---- 现有图索引 ----
        Map<String, String> labelToId = new HashMap<>();   // 标准化 label → 现有节点 id
        Set<String> existingNodeIds = new HashSet<>();
        if (existingNodes != null) {
            for (Map<String, Object> n : existingNodes) {
                String id = str(n.get("id"));
                if (id == null) continue;
                existingNodeIds.add(id);
                String key = norm(str(n.get("label")));
                if (!key.isEmpty()) labelToId.putIfAbsent(key, id);
            }
        }
        Set<String> existingEdgeIds = new HashSet<>();
        Set<String> existingEdgeSigs = new HashSet<>();    // from->to#rel 唯一签名
        if (existingEdges != null) {
            for (Map<String, Object> e : existingEdges) {
                String id = str(e.get("id"));
                if (id != null) existingEdgeIds.add(id);
                String sig = edgeSig(str(e.get("from")), str(e.get("to")),
                        str(e.get("rel_type")), str(e.get("label")));
                if (sig != null) existingEdgeSigs.add(sig);
            }
        }

        // ---- 1. add_nodes 重画回收：label 已存在 → 丢弃并重映射 ----
        ArrayNode outAddNodes = f.arrayNode();
        Map<String, String> idRemap = new HashMap<>(); // 被丢弃新节点 id → 现有节点 id
        Set<String> keptNewIds = new HashSet<>();
        int redrawn = 0;
        if (addNodes != null && addNodes.isArray()) {
            for (JsonNode n : addNodes) {
                String id = n.path("id").asText("");
                String key = norm(n.path("label").asText(""));
                String hit = key.isEmpty() ? null : labelToId.get(key);
                if (hit == null && existingNodeIds.contains(id)) hit = id; // 直接复用了现有 id 也算重画
                if (hit != null) {
                    if (!id.isEmpty()) idRemap.put(id, hit);
                    redrawn++;
                    continue;
                }
                outAddNodes.add(n);
                if (!id.isEmpty()) keptNewIds.add(id);
            }
        }
        if (redrawn > 0) {
            notices.add("已忽略 " + redrawn + " 个与现有图重复的节点（按名称合并到已有节点）");
        }

        // ---- 2. add_edges：端点重映射 + 去重 + 丢弃悬空 ----
        ArrayNode outAddEdges = f.arrayNode();
        int droppedEdges = 0;
        if (addEdges != null && addEdges.isArray()) {
            Set<String> newSigs = new HashSet<>();
            for (JsonNode e : addEdges) {
                if (!(e instanceof ObjectNode obj)) continue;
                ObjectNode copy = obj.deepCopy();
                String from = idRemap.getOrDefault(copy.path("from").asText(""), copy.path("from").asText(""));
                String to = idRemap.getOrDefault(copy.path("to").asText(""), copy.path("to").asText(""));
                copy.put("from", from);
                copy.put("to", to);
                boolean fromOk = existingNodeIds.contains(from) || keptNewIds.contains(from);
                boolean toOk = existingNodeIds.contains(to) || keptNewIds.contains(to);
                if (!fromOk || !toOk || from.equals(to)) { droppedEdges++; continue; }   // 悬空 / 自环
                String sig = edgeSig(from, to, copy.path("rel_type").asText(null), copy.path("label").asText(null));
                if (sig == null || existingEdgeSigs.contains(sig) || !newSigs.add(sig)) { // 与现有/本批重复
                    droppedEdges++;
                    continue;
                }
                outAddEdges.add(copy);
            }
        }
        if (droppedEdges > 0) {
            notices.add("已忽略 " + droppedEdges + " 条重复/悬空的关系");
        }

        // ---- 3. 删除预算：过滤不存在的 id，超限整批拦截 ----
        ArrayNode outRemoveNodes = filterExisting(removeNodes, existingNodeIds, f);
        ArrayNode outRemoveEdges = filterExisting(removeEdges, existingEdgeIds, f);
        int nodeCap = (int) Math.max(NODE_REMOVE_FLOOR,
                Math.ceil((existingNodes == null ? 0 : existingNodes.size()) * NODE_REMOVE_RATIO));
        int edgeCap = (int) Math.max(EDGE_REMOVE_FLOOR,
                Math.ceil((existingEdges == null ? 0 : existingEdges.size()) * EDGE_REMOVE_RATIO));
        if (outRemoveNodes.size() > nodeCap) {
            notices.add("已拦截一次性删除 " + outRemoveNodes.size() + " 个节点的请求（单轮上限 " + nodeCap
                    + "，防止误删）。如确需批量删除，请在画布多选后删除，或分多轮明确指定要删的节点。");
            outRemoveNodes = f.arrayNode();
        }
        if (outRemoveEdges.size() > edgeCap) {
            notices.add("已拦截一次性删除 " + outRemoveEdges.size() + " 条关系的请求（单轮上限 " + edgeCap + "，防止误删）。");
            outRemoveEdges = f.arrayNode();
        }

        // ---- 4. 补丁白名单：只留允许字段；改 id 剥掉，改边端点须指向现有节点 ----
        ArrayNode outUpdateNodes = sanitizePatches(updateNodes, existingNodeIds, NODE_PATCH_FIELDS, null, f);
        ArrayNode outUpdateEdges = sanitizePatches(updateEdges, existingEdgeIds, EDGE_PATCH_FIELDS, existingNodeIds, f);

        return new Guarded(outAddNodes, outAddEdges, outRemoveNodes, outRemoveEdges,
                outUpdateNodes, outUpdateEdges, notices);
    }

    /** 只保留引用了真实存在 id 的删除项。 */
    private static ArrayNode filterExisting(ArrayNode ids, Set<String> existing, JsonNodeFactory f) {
        ArrayNode out = f.arrayNode();
        if (ids == null) return out;
        for (JsonNode id : ids) {
            if (existing.contains(id.asText(""))) out.add(id);
        }
        return out;
    }

    /**
     * 补丁清洗：目标 id 必须真实存在；除 id（定位键）外仅保留白名单字段；
     * from/to（改边端点）额外要求新端点是现有节点 id；清洗后没有可改字段的丢弃。
     */
    private static ArrayNode sanitizePatches(ArrayNode patches, Set<String> existingIds,
                                             Set<String> allowed, Set<String> endpointIds,
                                             JsonNodeFactory f) {
        ArrayNode out = f.arrayNode();
        if (patches == null) return out;
        for (JsonNode p : patches) {
            if (!(p instanceof ObjectNode obj)) continue;
            String id = obj.path("id").asText("");
            if (!existingIds.contains(id)) continue;
            ObjectNode cleaned = f.objectNode();
            cleaned.put("id", id);
            int kept = 0;
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> en = it.next();
                String key = en.getKey();
                if (!allowed.contains(key)) continue;
                boolean isEndpoint = "from".equals(key) || "to".equals(key);
                if (isEndpoint && (endpointIds == null || !endpointIds.contains(en.getValue().asText("")))) {
                    continue; // 改端点必须指向现有节点，否则丢弃该字段
                }
                cleaned.set(key, en.getValue());
                kept++;
            }
            if (kept > 0) out.add(cleaned);
        }
        return out;
    }

    private static String edgeSig(String from, String to, String relType, String label) {
        if (from == null || to == null || from.isBlank() || to.isBlank()) return null;
        String rel = relType != null && !relType.isBlank() ? relType : (label == null ? "" : label);
        return from + "->" + to + "#" + norm(rel);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

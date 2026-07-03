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
 *   <li><b>重画回收</b>：add_nodes 里 label+type 与现有节点相同的一律丢弃并把其 id 重映射到现有节点
 *       （上下文被裁剪时 LLM 看不到全图，常会把已有概念再画一遍——这里把"重画"折叠成 no-op；
 *       同名但 type 明确不同视作两个概念，不折叠，空 type 作通配）；
 *       add_edges 端点跟随重映射，与现有边重复的（同 from/to/rel_type）也丢弃；</li>
 *   <li><b>删除预算</b>：单轮删除的节点/边数量有硬上限（绝对值 + 图规模占比取大者），
 *       超限视为疑似误删，整批拦截并提示用户改用画布多选删除或分次明确指令；
 *       引用不存在 id 的删除项直接过滤；</li>
 *   <li><b>补丁白名单</b>：update_nodes / update_edges 只允许修改白名单字段，禁止改 id；
 *       边的 from/to（改端点）允许，但新端点必须真实存在、改后不得自环或与现有关系重复，
 *       非法端点改动被剥离并通过 notice 告知。</li>
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
    /**
     * 边补丁允许修改的字段。from/to（改端点）与 prompt 的教学保持一致地放行，
     * 但须通过端点专门校验：新端点必须真实存在、改后不得自环/与现有边重复，
     * 非法端点字段被剥离并产生 notice（见 apply 内的 update_edges 处理）。
     */
    private static final Set<String> EDGE_PATCH_FIELDS = Set.of(
            "label", "rel_type", "rule_driven", "constraints", "from", "to",
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
        // 标准化 label → 候选 {id, 标准化 type}。折叠判等看 label + type：
        // 同名不同 type 是两个概念（如 rule「风险」与 metric「风险」），不得互相折叠；
        // 空 type 作通配（旧数据/LLM 省略 type 时仍能按 label 折叠）。
        Map<String, List<String[]>> labelIndex = new HashMap<>();
        Set<String> existingNodeIds = new HashSet<>();
        if (existingNodes != null) {
            for (Map<String, Object> n : existingNodes) {
                String id = str(n.get("id"));
                if (id == null) continue;
                existingNodeIds.add(id);
                registerLabel(labelIndex, str(n.get("label")), str(n.get("type")), id);
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
                String label = n.path("label").asText("");
                String type = n.path("type").asText("");
                String hit = lookupLabel(labelIndex, label, type);
                if (hit == null && existingNodeIds.contains(id)) hit = id; // 直接复用了现有 id 也算重画
                if (hit != null) {
                    if (!id.isEmpty()) idRemap.put(id, hit);
                    redrawn++;
                    continue;
                }
                outAddNodes.add(n);
                if (!id.isEmpty()) {
                    keptNewIds.add(id);
                    // 同批内的重复 label(+type) 也折叠：后续同名 add 重映射到本节点，而不是双双入图
                    registerLabel(labelIndex, label, type, id);
                }
            }
        }
        if (redrawn > 0) {
            notices.add("已忽略 " + redrawn + " 个重复的节点（按名称合并到同名节点）");
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

        // ---- 4. 补丁白名单：只留允许字段（改 id 一律剥掉）；边端点改动再过专门校验 ----
        ArrayNode outUpdateNodes = sanitizePatches(updateNodes, existingNodeIds, NODE_PATCH_FIELDS, f);
        ArrayNode outUpdateEdges = sanitizePatches(updateEdges, existingEdgeIds, EDGE_PATCH_FIELDS, f);
        outUpdateEdges = sanitizeEdgeEndpointPatches(outUpdateEdges, existingEdges, existingNodeIds,
                keptNewIds, existingEdgeSigs, outAddEdges, notices, f);

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
     * 补丁清洗：目标 id 必须真实存在；除 id（定位键）外仅保留白名单字段
     * （from/to 不在白名单，改边端点须显式走删 + 加）；清洗后没有可改字段的丢弃。
     */
    private static ArrayNode sanitizePatches(ArrayNode patches, Set<String> existingIds,
                                             Set<String> allowed, JsonNodeFactory f) {
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
                cleaned.set(key, en.getValue());
                kept++;
            }
            if (kept > 0) out.add(cleaned);
        }
        return out;
    }

    /** 把节点按 (标准化 label, 标准化 type) 注册进折叠索引。空 label 不注册。 */
    private static void registerLabel(Map<String, List<String[]>> index, String label, String type, String id) {
        String key = norm(label);
        if (key.isEmpty() || id == null || id.isEmpty()) return;
        index.computeIfAbsent(key, k -> new ArrayList<>()).add(new String[]{id, norm(type)});
    }

    /**
     * 折叠索引查询：同 label 下优先 type 精确匹配；查询方或候选方 type 为空视作通配；
     * 同名但 type 明确不同 → 不折叠（返回 null，让其作为新节点入图）。
     */
    private static String lookupLabel(Map<String, List<String[]>> index, String label, String type) {
        String key = norm(label);
        if (key.isEmpty()) return null;
        List<String[]> cands = index.get(key);
        if (cands == null || cands.isEmpty()) return null;
        String t = norm(type);
        if (t.isEmpty()) return cands.get(0)[0]; // 查询方未给 type：按旧行为折叠到首个同名节点
        String blankHit = null;
        for (String[] c : cands) {
            if (t.equals(c[1])) return c[0];
            if (c[1].isEmpty() && blankHit == null) blankHit = c[0];
        }
        return blankHit;
    }

    /**
     * 边端点补丁校验：带 from/to 的补丁，改后的两端必须真实存在（现有节点或本轮新增节点）、
     * 不得自环、不得与现有边/本轮新增边/本轮其它端点补丁改出的边重复（自身旧签名除外）。
     * 非法时只剥离 from/to（保留其余可改字段）；剥完只剩 id 的整条丢弃。统一出一条 notice。
     */
    private static ArrayNode sanitizeEdgeEndpointPatches(ArrayNode patches,
                                                         List<Map<String, Object>> existingEdges,
                                                         Set<String> existingNodeIds,
                                                         Set<String> keptNewIds,
                                                         Set<String> existingEdgeSigs,
                                                         ArrayNode addedEdges,
                                                         List<String> notices,
                                                         JsonNodeFactory f) {
        if (patches == null || patches.isEmpty()) return patches == null ? f.arrayNode() : patches;
        Map<String, Map<String, Object>> edgeById = new HashMap<>();
        if (existingEdges != null) {
            for (Map<String, Object> e : existingEdges) {
                String id = str(e.get("id"));
                if (id != null) edgeById.put(id, e);
            }
        }
        // 已被占用的边签名：现有边 + 本轮新增边 + 已接受的端点补丁，防补丁改出重复边
        Set<String> takenSigs = new HashSet<>(existingEdgeSigs);
        if (addedEdges != null) {
            for (JsonNode e : addedEdges) {
                String sig = edgeSig(e.path("from").asText(""), e.path("to").asText(""),
                        e.path("rel_type").asText(null), e.path("label").asText(null));
                if (sig != null) takenSigs.add(sig);
            }
        }
        ArrayNode out = f.arrayNode();
        int stripped = 0;
        for (JsonNode p : patches) {
            if (!(p instanceof ObjectNode obj)) continue;
            boolean hasFrom = obj.hasNonNull("from");
            boolean hasTo = obj.hasNonNull("to");
            if (!hasFrom && !hasTo) { out.add(obj); continue; }
            Map<String, Object> cur = edgeById.get(obj.path("id").asText(""));
            String curFrom = cur == null ? null : str(cur.get("from"));
            String curTo = cur == null ? null : str(cur.get("to"));
            String newFrom = hasFrom ? obj.path("from").asText("") : curFrom;
            String newTo = hasTo ? obj.path("to").asText("") : curTo;
            boolean fromOk = newFrom != null && !newFrom.isBlank()
                    && (existingNodeIds.contains(newFrom) || keptNewIds.contains(newFrom));
            boolean toOk = newTo != null && !newTo.isBlank()
                    && (existingNodeIds.contains(newTo) || keptNewIds.contains(newTo));
            boolean valid = fromOk && toOk && !newFrom.equals(newTo);
            if (valid) {
                // 补丁可能同时改 rel_type / label，签名按“改后生效值”计算
                String rel = obj.hasNonNull("rel_type") ? obj.path("rel_type").asText(null)
                        : (cur == null ? null : str(cur.get("rel_type")));
                String lbl = obj.hasNonNull("label") ? obj.path("label").asText(null)
                        : (cur == null ? null : str(cur.get("label")));
                String newSig = edgeSig(newFrom, newTo, rel, lbl);
                String oldSig = cur == null ? null
                        : edgeSig(curFrom, curTo, str(cur.get("rel_type")), str(cur.get("label")));
                if (newSig != null && !newSig.equals(oldSig) && !takenSigs.add(newSig)) valid = false;
            }
            if (!valid) {
                obj.remove("from");
                obj.remove("to");
                stripped++;
                if (obj.size() <= 1) continue; // 只剩 id，无可改字段
            }
            out.add(obj);
        }
        if (stripped > 0) {
            notices.add("已拦截 " + stripped + " 条关系的端点改动（新端点不存在、自环或与现有关系重复），其余字段照常生效");
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

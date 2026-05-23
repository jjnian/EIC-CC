package com.tuiyan.backend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 节点 id 加盐重写：避免不同导入 / fork 批次的 id 互相冲突。
 * <p>两个使用场景：
 * <ol>
 *   <li>extract 流程（文档抽取草稿）：{@link #applyImportSalt} 给 LLM 草稿的 add_nodes/add_edges 整体加 salt 前缀，
 *       并深度扫描节点字段，命中 idMap 时替换 —— 因为同一份文档可能被反复导入，必须确保两次导入的 id 不冲突；</li>
 *   <li>predict 流程（fork 推演）：{@link #applyPredictionIdSalt} 仅对本批 chain 内、严格匹配 {@code ^p_\d+$} 的 raw id 改写
 *       —— LLM 输出的占位 id（p_1、p_2…）每次都从 1 开始，多次 fork 时会重复，需要加盐隔离。</li>
 * </ol>
 */
public final class IdSaltRewriter {

    private static final ObjectMapper M = new ObjectMapper();

    private IdSaltRewriter() {}

    /**
     * 给 extract 草稿加 salt 前缀。
     * <p>新 id 形如 {@code imp<salt>_<原id>}；边的 id 若原本为空则生成 {@code impe<salt>_<序号>}。
     * 节点 / 边内部所有引用旧 id 的字符串字段（除节点自身 id 外）也会被一并改写，
     * 保证图结构在改写后仍然连通。
     *
     * @return {@code {nodes:[...], edges:[...]}} 形状的 ObjectNode
     */
    public static JsonNode applyImportSalt(JsonNode draft, String salt) {
        ArrayNode srcNodes = draft.has("add_nodes") && draft.get("add_nodes").isArray()
                ? (ArrayNode) draft.get("add_nodes") : M.createArrayNode();
        ArrayNode srcEdges = draft.has("add_edges") && draft.get("add_edges").isArray()
                ? (ArrayNode) draft.get("add_edges") : M.createArrayNode();

        // 第一遍：建立 旧id → 新id 的映射表
        Map<String, String> idMap = new HashMap<>();
        for (JsonNode n : srcNodes) {
            if (!n.has("id")) continue;
            String oldId = n.get("id").asText();
            if (oldId.isEmpty()) continue;
            idMap.put(oldId, "imp" + salt + "_" + oldId);
        }

        // 第二遍：节点 id 改写，并递归替换其它字段里出现的旧 id 引用
        ArrayNode outNodes = M.createArrayNode();
        for (JsonNode n : srcNodes) {
            if (!n.has("id")) continue;
            ObjectNode copy = n.deepCopy();
            String oldId = copy.get("id").asText();
            String newId = idMap.getOrDefault(oldId, "imp" + salt + "_" + oldId);
            copy.put("id", newId);
            // skipField="id"：避免 id 字段被二次替换（前面已经写过新值）
            remapStringsDeep(copy, idMap, "id");
            outNodes.add(copy);
        }

        // 边：from/to 必须查 idMap；边自身的 id 单独生成或继承前缀
        ArrayNode outEdges = M.createArrayNode();
        int eIdx = 0;
        for (JsonNode e : srcEdges) {
            String from = e.path("from").asText("");
            String to = e.path("to").asText("");
            String newFrom = idMap.getOrDefault(from, from);
            String newTo = idMap.getOrDefault(to, to);
            ObjectNode copy = e.deepCopy();
            copy.put("from", newFrom);
            copy.put("to", newTo);
            String oldEid = e.path("id").asText("");
            copy.put("id", oldEid.isEmpty()
                    ? "impe" + salt + "_" + (++eIdx)
                    : "imp" + salt + "_" + oldEid);
            remapStringsDeep(copy, idMap, "id");
            outEdges.add(copy);
        }

        ObjectNode root = M.createObjectNode();
        root.set("nodes", outNodes);
        root.set("edges", outEdges);
        return root;
    }

    /**
     * predict fork id 重写：只对本批 chain 内、严格匹配 {@code ^p_\d+$} 的 raw id 改写为 {@code p<salt>_N}。
     * <p>判断规则：
     * <ul>
     *   <li>idSalt 为空（非 fork 情形）→ 原样返回；</li>
     *   <li>raw 不符合 p_数字 模式（如 trunk 真实节点 id）→ 不改写；</li>
     *   <li>raw 不在 currentChainIds 中（即引用祖先批次的 p1a_2 等）→ 不改写。</li>
     * </ul>
     * 这保证了 fork 时只对"本批 LLM 新生成的占位 id"加盐，保留对祖先 / trunk 节点的引用关系。
     */
    public static String applyPredictionIdSalt(String raw, String idSalt, Set<String> currentChainIds) {
        if (idSalt == null || idSalt.isEmpty()) return raw;
        if (raw == null) return null;
        if (!raw.matches("p_\\d+")) return raw;
        if (currentChainIds == null || !currentChainIds.contains(raw)) return raw;
        return "p" + idSalt + "_" + raw.substring(2);
    }

    /**
     * 递归把节点 / 边内所有字符串字段中等于 idMap.key 的值替换为 idMap.value。
     * @param skipField 顶层要跳过的字段名（如 "id"），防止节点自身 id 字段被二次替换；递归到子节点时不再跳过
     */
    private static void remapStringsDeep(JsonNode node, Map<String, String> idMap, String skipField) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String fieldName = entry.getKey();
                JsonNode v = entry.getValue();
                if (v.isTextual()) {
                    if (skipField != null && skipField.equals(fieldName)) continue;
                    String mapped = idMap.get(v.asText());
                    if (mapped != null) obj.put(fieldName, mapped);
                } else if (v.isContainerNode()) {
                    // 子节点不再跳过任何字段名，第二层及以下的 "id" 字段也是引用
                    remapStringsDeep(v, idMap, null);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode v = arr.get(i);
                if (v.isTextual()) {
                    String mapped = idMap.get(v.asText());
                    if (mapped != null) arr.set(i, arr.textNode(mapped));
                } else if (v.isContainerNode()) {
                    remapStringsDeep(v, idMap, null);
                }
            }
        }
    }
}

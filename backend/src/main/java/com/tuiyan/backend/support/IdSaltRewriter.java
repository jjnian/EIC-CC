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
 * id salt 重写:
 * 1) extract 流程:applyImportSalt — 给 LLM 草稿的 add_nodes/add_edges 整体加 salt 前缀,
 *    并深度扫描节点字段,命中 idMap 时替换。
 * 2) predict 流程:applyPredictionIdSalt — 仅对本批 chain 内、严格匹配 ^p_\d+$ 的 raw id 改写。
 */
public final class IdSaltRewriter {

    private static final ObjectMapper M = new ObjectMapper();

    private IdSaltRewriter() {}

    /**
     * 给 extract 草稿加 salt 前缀。返回 {nodes, edges} 的 root ObjectNode。
     */
    public static JsonNode applyImportSalt(JsonNode draft, String salt) {
        ArrayNode srcNodes = draft.has("add_nodes") && draft.get("add_nodes").isArray()
                ? (ArrayNode) draft.get("add_nodes") : M.createArrayNode();
        ArrayNode srcEdges = draft.has("add_edges") && draft.get("add_edges").isArray()
                ? (ArrayNode) draft.get("add_edges") : M.createArrayNode();

        Map<String, String> idMap = new HashMap<>();
        for (JsonNode n : srcNodes) {
            if (!n.has("id")) continue;
            String oldId = n.get("id").asText();
            if (oldId.isEmpty()) continue;
            idMap.put(oldId, "imp" + salt + "_" + oldId);
        }

        ArrayNode outNodes = M.createArrayNode();
        for (JsonNode n : srcNodes) {
            if (!n.has("id")) continue;
            ObjectNode copy = n.deepCopy();
            String oldId = copy.get("id").asText();
            String newId = idMap.getOrDefault(oldId, "imp" + salt + "_" + oldId);
            copy.put("id", newId);
            remapStringsDeep(copy, idMap, "id");
            outNodes.add(copy);
        }

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
     * predict fork id 重写:只对本批 chain 内、严格匹配 ^p_\d+$ 的 raw id 改写为 p<salt>_N。
     * 引用祖先节点的 pXXX_N、trunk 节点 id 原样保留。
     */
    public static String applyPredictionIdSalt(String raw, String idSalt, Set<String> currentChainIds) {
        if (idSalt == null || idSalt.isEmpty()) return raw;
        if (raw == null) return null;
        if (!raw.matches("p_\\d+")) return raw;
        if (currentChainIds == null || !currentChainIds.contains(raw)) return raw;
        return "p" + idSalt + "_" + raw.substring(2);
    }

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

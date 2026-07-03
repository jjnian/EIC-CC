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
 * 节点 id 加盐重写：避免不同导入批次的 id 互相冲突。
 * <p>extract 流程（文档抽取草稿）：{@link #applyImportSalt} 给 LLM 草稿的 add_nodes/add_edges 整体加 salt 前缀，
 * 并深度扫描节点字段，命中 idMap 时替换 —— 因为同一份文档可能被反复导入，必须确保两次导入的 id 不冲突。
 */
public final class IdSaltRewriter {

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * 展示性文本字段：内容是给人看的文案（名称/证据引文/描述/属性键值），不是 id 引用。
     * 深度重写时跳过——否则 label 恰好叫 "n1" 这类与旧 id 撞名的文本会被误改写成加盐 id。
     */
    private static final Set<String> DISPLAY_TEXT_FIELDS = Set.of(
            "label", "evidence", "desc", "description", "note", "title", "key", "value", "domain");

    /** 展示性字符串数组字段：别名列表 / 来源表名，同样不是 id 引用，整个子树跳过。 */
    private static final Set<String> DISPLAY_ARRAY_FIELDS = Set.of("aliases", "derived_tables");

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
     * 递归把节点 / 边内所有字符串字段中等于 idMap.key 的值替换为 idMap.value。
     * <p>展示性字段（{@link #DISPLAY_TEXT_FIELDS} / {@link #DISPLAY_ARRAY_FIELDS}）跳过：
     * 它们承载的是文案而非 id 引用，与旧 id 撞名（如 label 恰好叫 "n1"）时不能被改写。
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
                    if (DISPLAY_TEXT_FIELDS.contains(fieldName)) continue;
                    String mapped = idMap.get(v.asText());
                    if (mapped != null) obj.put(fieldName, mapped);
                } else if (v.isContainerNode()) {
                    if (DISPLAY_ARRAY_FIELDS.contains(fieldName)) continue; // 展示性数组整棵跳过
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

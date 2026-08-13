package com.tuiyan.backend.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.service.llm.GraphPromptBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 血缘补齐工具：用本次对话注入的 dbSchemas 反查每个节点/边的 derived_tables，补齐来源信息。
 * LLM 在 chat 流里只被要求填 derived_tables，数据源名和库名要在服务端按表名兜底，
 * 否则前端"数据来源"卡片就会只显示来源表。
 * <p>产出两层：
 * <ul>
 *   <li><b>单值兼容字段</b> {@code derived_source} / {@code derived_database}：仅在缺失时按
 *       首个命中的来源填入（沿用旧行为，前端老展示继续可用）；</li>
 *   <li><b>多来源字段</b> {@code derived_sources}：聚合该元素全部 derived_tables 命中的
 *       所有数据源——节点引用了多个数据源的表时不再只标第一个。
 *       形如 {@code [{source, database, tables:[...]}, ...]}，命中顺序保持 derived_tables 原序。
 *       同名表存在于多个数据源时各来源都会入列（歧义交由用户在图上核对）。</li>
 * </ul>
 */
public final class DerivedSourceStamper {

    private DerivedSourceStamper() {}

    public static void stamp(JsonNode arr, List<GraphPromptBuilder.DbSchema> dbSchemas) {
        if (arr == null || !arr.isArray() || dbSchemas == null || dbSchemas.isEmpty()) return;
        // 表名（标准化）→ 拥有它的全部数据源（保持注入顺序）。
        // 同时以「库名.表名」入索引：LLM 输出限定名时可精确命中，不受同名表歧义影响。
        Map<String, List<GraphPromptBuilder.DbSchema>> tableOwners = new HashMap<>();
        Map<String, GraphPromptBuilder.DbSchema> qualifiedOwner = new HashMap<>();
        for (GraphPromptBuilder.DbSchema s : dbSchemas) {
            if (s == null || s.tables() == null) continue;
            for (String t : s.tables()) {
                String norm = normalizeTable(t);
                if (norm.isEmpty()) continue;
                List<GraphPromptBuilder.DbSchema> owners =
                        tableOwners.computeIfAbsent(norm, k -> new ArrayList<>());
                if (!owners.contains(s)) owners.add(s);
                if (s.database() != null && !s.database().isBlank()) {
                    qualifiedOwner.putIfAbsent(s.database().toLowerCase(Locale.ROOT) + "." + norm, s);
                }
            }
        }
        if (tableOwners.isEmpty()) return;

        for (JsonNode node : arr) {
            if (!(node instanceof ObjectNode obj)) continue;
            JsonNode tables = obj.path("derived_tables");
            if (!tables.isArray() || tables.isEmpty()) continue;

            // 聚合：来源 → 它拥有的本元素引用表（保持 derived_tables 原序）
            Map<GraphPromptBuilder.DbSchema, List<String>> hits = new LinkedHashMap<>();
            for (JsonNode tn : tables) {
                String raw = tn.asText("");
                String norm = normalizeTable(raw);
                if (norm.isEmpty()) continue;
                // 限定名精确命中 → 只归属该来源；裸表名 → 归属所有拥有它的来源
                GraphPromptBuilder.DbSchema exact = qualifiedOwner.get(norm);
                if (exact != null) {
                    hits.computeIfAbsent(exact, k -> new ArrayList<>()).add(raw);
                    continue;
                }
                String bare = norm.lastIndexOf('.') >= 0 ? norm.substring(norm.lastIndexOf('.') + 1) : norm;
                List<GraphPromptBuilder.DbSchema> owners = tableOwners.get(norm);
                if (owners == null) owners = tableOwners.get(bare);
                if (owners == null) continue;
                for (GraphPromptBuilder.DbSchema s : owners) {
                    hits.computeIfAbsent(s, k -> new ArrayList<>()).add(raw);
                }
            }
            if (hits.isEmpty()) continue;

            // 单值兼容字段：缺失时按首个命中来源补齐
            GraphPromptBuilder.DbSchema first = hits.keySet().iterator().next();
            if (obj.path("derived_source").asText("").isBlank() && first.sourceName() != null) {
                obj.put("derived_source", first.sourceName());
            }
            if (obj.path("derived_database").asText("").isBlank() && first.database() != null) {
                obj.put("derived_database", first.database());
            }

            // 多来源字段：完整聚合（覆盖写——它由服务端推导，不接受 LLM 自造值）
            ArrayNode sources = obj.arrayNode();
            for (Map.Entry<GraphPromptBuilder.DbSchema, List<String>> en : hits.entrySet()) {
                ObjectNode one = obj.objectNode();
                if (en.getKey().sourceName() != null) one.put("source", en.getKey().sourceName());
                if (en.getKey().database() != null) one.put("database", en.getKey().database());
                ArrayNode ts = one.arrayNode();
                for (String t : en.getValue()) ts.add(t);
                one.set("tables", ts);
                sources.add(one);
            }
            obj.set("derived_sources", sources);
        }
    }

    /** 表名标准化：去首尾空白与反引号/引号包裹，统一小写。保留中间的 db.table 结构。 */
    private static String normalizeTable(String t) {
        if (t == null) return "";
        String s = t.trim().replace("`", "").replace("\"", "").replace("'", "");
        return s.toLowerCase(Locale.ROOT);
    }
}

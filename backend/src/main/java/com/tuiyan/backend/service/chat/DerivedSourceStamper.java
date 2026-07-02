package com.tuiyan.backend.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.service.llm.GraphPromptBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 血缘补齐工具：用本次对话注入的 dbSchemas 反查每个节点/边的 derived_tables，把缺失的
 * derived_source / derived_database 补齐。LLM 在 chat 流里只被要求填 derived_tables，
 * 数据源名和库名要在服务端按表名兜底，否则前端"数据来源"卡片就会只显示来源表。
 */
public final class DerivedSourceStamper {

    private DerivedSourceStamper() {}

    public static void stamp(JsonNode arr, List<GraphPromptBuilder.DbSchema> dbSchemas) {
        if (arr == null || !arr.isArray() || dbSchemas == null || dbSchemas.isEmpty()) return;
        // 表名（标准化）→ {数据源名, 库名}；多个数据源含同名表时先到先得，避免误标。
        // 同时以「库名.表名」入索引：LLM 输出限定名时也能命中。
        Map<String, String[]> tableIndex = new HashMap<>();
        for (GraphPromptBuilder.DbSchema s : dbSchemas) {
            if (s == null || s.tables() == null) continue;
            for (String t : s.tables()) {
                String norm = normalizeTable(t);
                if (norm.isEmpty()) continue;
                String[] hit = new String[] { s.sourceName(), s.database() };
                tableIndex.putIfAbsent(norm, hit);
                if (s.database() != null && !s.database().isBlank()) {
                    tableIndex.putIfAbsent(s.database().toLowerCase(Locale.ROOT) + "." + norm, hit);
                }
            }
        }
        if (tableIndex.isEmpty()) return;
        for (JsonNode node : arr) {
            if (!(node instanceof ObjectNode obj)) continue;
            JsonNode tables = obj.path("derived_tables");
            if (!tables.isArray() || tables.isEmpty()) continue;
            for (JsonNode tn : tables) {
                String[] hit = lookup(tableIndex, tn.asText(""));
                if (hit == null) continue;
                if (obj.path("derived_source").asText("").isBlank() && hit[0] != null) {
                    obj.put("derived_source", hit[0]);
                }
                if (obj.path("derived_database").asText("").isBlank() && hit[1] != null) {
                    obj.put("derived_database", hit[1]);
                }
                break;
            }
        }
    }

    /** 先按完整名（可能带库前缀）查，再退化为最后一段（去掉 db./schema. 前缀）查。 */
    private static String[] lookup(Map<String, String[]> tableIndex, String raw) {
        String norm = normalizeTable(raw);
        if (norm.isEmpty()) return null;
        String[] hit = tableIndex.get(norm);
        if (hit != null) return hit;
        int dot = norm.lastIndexOf('.');
        return dot >= 0 ? tableIndex.get(norm.substring(dot + 1)) : null;
    }

    /** 表名标准化：去首尾空白与反引号/引号包裹，统一小写。保留中间的 db.table 结构。 */
    private static String normalizeTable(String t) {
        if (t == null) return "";
        String s = t.trim().replace("`", "").replace("\"", "").replace("'", "");
        return s.toLowerCase(Locale.ROOT);
    }
}

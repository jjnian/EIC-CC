package com.tuiyan.backend.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 节点 props 与图谱 nodes/edges 的 JSON 编解码工具：
 * Domain 层用 {@code Map<String,Object>} 表达节点/边的灵活字段，DB 中拆成扁平列 + props 子表。
 * 这里集中处理 {@code Object → (value, valueType)} 的双向转换。
 */
public class JsonCodec {
    private static final Logger log = LoggerFactory.getLogger(JsonCodec.class);

    private final ObjectMapper objectMapper;

    public JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 把任意值序列化为 (value, valueType) 二元组，便于以 TEXT 单列存储。 */
    public ValueAndType encode(Object v) {
        if (v == null) return new ValueAndType(null, "string");
        if (v instanceof String s) return new ValueAndType(s, "string");
        if (v instanceof Boolean b) return new ValueAndType(b.toString(), "bool");
        if (v instanceof Number n) return new ValueAndType(n.toString(), "number");
        // 复杂对象（List / Map / 嵌套结构）走 JSON 序列化
        try {
            return new ValueAndType(objectMapper.writeValueAsString(v), "json");
        } catch (Exception e) {
            log.warn("encode failed, fallback to string: {}", e.toString());
            return new ValueAndType(String.valueOf(v), "string");
        }
    }

    /** 与 {@link #encode} 对称：按 valueType 把 TEXT 还原为对应类型。 */
    public Object decode(String value, String valueType) {
        if (value == null) return null;
        if (valueType == null) return value;
        return switch (valueType) {
            case "number" -> {
                try {
                    if (value.contains(".")) yield Double.parseDouble(value);
                    yield Long.parseLong(value);
                } catch (NumberFormatException nfe) {
                    yield value;
                }
            }
            case "bool" -> Boolean.parseBoolean(value);
            case "json" -> {
                try {
                    yield objectMapper.readValue(value, Object.class);
                } catch (Exception e) {
                    yield value;
                }
            }
            default -> value;
        };
    }

    /** 序列化任意对象为 JSON 字符串（List / Map 等用于 *_json 字段）。 */
    public String toJson(Object v) {
        if (v == null) return null;
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            log.warn("toJson failed: {}", e.toString());
            return null;
        }
    }

    /** 反序列化为 List<Map<String,Object>>，用于图谱的 nodes/edges。 */
    public List<Map<String, Object>> readMapList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("readMapList failed: {}", e.toString());
            return new ArrayList<>();
        }
    }

    /** 反序列化为 List<String>，用于 seeds 等字符串数组字段。 */
    public List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("readStringList failed: {}", e.toString());
            return new ArrayList<>();
        }
    }

    /** 反序列化为指定类型。 */
    public <T> T readValue(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.warn("readValue<{}> failed: {}", clazz.getSimpleName(), e.toString());
            return null;
        }
    }

    /** 反序列化为 List。 */
    public <T> List<T> readList(String json, Class<T> elementClass) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementClass));
        } catch (Exception e) {
            log.warn("readList<{}> failed: {}", elementClass.getSimpleName(), e.toString());
            return new ArrayList<>();
        }
    }

    public record ValueAndType(String value, String valueType) {}
}

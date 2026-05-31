package com.tuiyan.backend.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LLM 响应解析：从协议响应中抽取文本内容、剥除 markdown 代码块包装。
 * <p>从 {@link LlmHttpClient} 拆出的纯解析逻辑，无 IO、无状态。
 */
@Component
public class LlmResponseParser {

    private static final Logger log = LoggerFactory.getLogger(LlmResponseParser.class);

    /**
     * 从 LLM 响应中抽出文本内容。
     * <p>Anthropic 响应是 content[] 数组，需要拼接所有 type=text 的 block；
     * OpenAI 直接取 choices[0].message.content。
     * <p>当配置为 Anthropic 但中转站实际返回 OpenAI 格式时自动回退，避免丢失全部内容。
     */
    public String extractContent(JsonNode responseJson, boolean anthropic) {
        if (anthropic) {
            StringBuilder sb = new StringBuilder();
            JsonNode arr = responseJson.path("content");
            if (arr.isArray()) {
                for (JsonNode block : arr) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
            // 中转站可能虽声明 anthropic 协议但实际返回 OpenAI 格式，回退尝试
            String openAiFallback = responseJson.path("choices").path(0).path("message").path("content").asText("");
            if (!openAiFallback.isEmpty()) {
                log.warn("[LLM] 配置为 anthropic 协议但响应为 OpenAI 格式，已自动回退解析；建议检查中转站协议配置（改为 protocol: openai）");
                return openAiFallback;
            }
            return "";
        }
        return responseJson.path("choices").path(0).path("message").path("content").asText("");
    }

    /** 剥掉常见的 ```json``` markdown 包装，让上层始终拿到纯 JSON 字符串。 */
    public String stripJsonFence(String content) {
        if (content == null) return "{}";
        String s = content.trim();
        if (s.isEmpty()) return "{}";
        // 已经是裸 JSON，直接返回
        if (s.startsWith("{") || s.startsWith("[")) return s;
        // 尝试从 markdown 代码块中提取 JSON
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "```(?:json)?\\s*\\n([\\s\\S]*?)\\n\\s*```"
        ).matcher(s);
        if (m.find()) {
            String extracted = m.group(1).trim();
            if (!extracted.isEmpty()) return extracted;
        }
        // 兜底：去掉首尾的 ``` 行
        s = s.replaceFirst("(?i)^\\s*```(?:json)?\\s*\\n?", "");
        s = s.replaceFirst("\\s*```\\s*$", "");
        s = s.trim();
        return s.isEmpty() ? "{}" : s;
    }
}

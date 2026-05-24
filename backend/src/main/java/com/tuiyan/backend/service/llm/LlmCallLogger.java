package com.tuiyan.backend.service.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM 调用日志助手：把请求 / 响应 / 上游错误以结构化方式打到日志。
 * <p>单条文本超过 {@link #LOG_TEXT_MAX} 字符会被截断，避免大 base64 / JSON 撑爆日志。
 */
@Component
public class LlmCallLogger {

    private static final Logger log = LoggerFactory.getLogger(LlmCallLogger.class);

    private static final int LOG_TEXT_MAX = 2000;

    private static String truncateForLog(String s) {
        if (s == null) return "";
        if (s.length() <= LOG_TEXT_MAX) return s;
        return s.substring(0, LOG_TEXT_MAX) + "…(已截断,原长 " + s.length() + ")";
    }

    /** 打印一次发给 LLM 的对话内容（system + history + user prompt + 附件统计）。 */
    public void logConversation(String tag, String modelName, String systemPrompt,
                                 List<Map<String, Object>> history, String userText,
                                 List<Map<String, Object>> attachments) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== [").append(tag).append("] → LLM 请求 model=").append(modelName).append(" ==========\n");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append("[system]\n").append(truncateForLog(systemPrompt)).append("\n");
        }
        int hi = 0;
        if (history != null) {
            for (Map<String, Object> msg : history) {
                String role = String.valueOf(msg.get("role"));
                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                sb.append("[history#").append(hi++).append(" ").append(role).append("]\n")
                  .append(truncateForLog(String.valueOf(msg.get("content")))).append("\n");
            }
        }
        sb.append("[user]\n").append(truncateForLog(userText == null ? "" : userText)).append("\n");
        if (attachments != null && !attachments.isEmpty()) {
            int imgCount = 0;
            for (Map<String, Object> a : attachments) {
                if ("image".equals(String.valueOf(a.get("type")))) imgCount++;
            }
            sb.append("[attachments] images=").append(imgCount).append(" total=").append(attachments.size()).append("\n");
        }
        sb.append("==========================================================");
        log.info(sb.toString());
    }

    /** 打印从 LLM 收到的最终文本内容（已去掉 ```json 包装）。 */
    public void logLlmResponse(String tag, String modelName, long elapsedMs, String content) {
        log.info("\n========== [{}] ← LLM 响应 model={} 耗时={}ms 长度={} ==========\n{}\n==========================================================",
                tag, modelName, elapsedMs, content == null ? 0 : content.length(), truncateForLog(content));
    }

    /** 截断上游错误体到前 1000 字符记录，避免冗长 HTML / JSON 充斥日志。 */
    public void logUpstreamError(String where, int status, String body) {
        String snippet = body == null ? "" : body.substring(0, Math.min(body.length(), 1000));
        log.warn("LLM upstream error in {}: HTTP {} body[:1000]={}", where, status, snippet);
    }
}

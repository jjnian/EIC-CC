package com.tuiyan.backend.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * SSE 流式响应解析器：支持 OpenAI 兼容格式和 Anthropic Messages 格式。
 * <p>把 LLM 返回的 SSE 行逐条解析，把文本增量通过 {@link SseEmitter} 推给前端，
 * 同时累积完整 content 返回给调用方做后续 JSON 解析。
 */
@Component
public class LlmStreamParser {

    private static final Logger log = LoggerFactory.getLogger(LlmStreamParser.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析 OpenAI 兼容流：每行 "data: &lt;json&gt;"，累积 delta.content；遇 [DONE] 结束。
     */
    public StringBuilder parseOpenAI(BufferedReader reader, SseEmitter emitter) throws IOException {
        StringBuilder fullContent = new StringBuilder();
        StringBuilder rawDump = new StringBuilder();
        int rawLineCount = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (rawLineCount < 10) {
                rawDump.append(line).append("\n");
                rawLineCount++;
            }
            String data = sseData(line);
            if (data == null) continue;
            if ("[DONE]".equals(data)) break;
            try {
                JsonNode chunk = objectMapper.readTree(data);
                String delta = chunk.path("choices").path(0).path("delta").path("content").asText("");
                if (delta.isEmpty()) continue;
                fullContent.append(delta);
                try {
                    emitter.send(SseEmitter.event().name("text").data(delta));
                } catch (IOException sendErr) {
                    log.warn("emit chunk failed (client disconnected?): {}", sendErr.toString());
                    break;
                }
            } catch (IOException parseErr) {
                log.warn("openai stream chunk parse failed: {}", parseErr.toString());
            }
        }
        if (fullContent.length() == 0) {
            String raw = rawDump.toString();
            if (raw.contains("<!doctype html>") || raw.contains("<!DOCTYPE html>") || raw.contains("<html")) {
                log.error("[LLM-stream] openai 端点返回 HTML 页面而非 SSE 流，请检查 base-url 是否缺少 /v1 路径前缀");
            }
            log.warn("[LLM-stream] openai 流为空 已读 {} 行 原始前 10 行 dump:\n{}", rawLineCount, raw);
        }
        return fullContent;
    }

    /**
     * 解析 Anthropic SSE 流：事件类型由 event: 行决定，content_block_delta 携带文本增量。
     * <p>同时处理 text_delta 和 input_json_delta，让 JSON 模式响应也能流式收齐。
     * <p>兼容中转站返回 OpenAI 格式的情况：自动检测 choices[].delta.content 并回退。
     */
    public StringBuilder parseAnthropic(BufferedReader reader, SseEmitter emitter) throws IOException {
        StringBuilder fullContent = new StringBuilder();
        StringBuilder rawDump = new StringBuilder();
        int rawLineCount = 0;
        boolean detectedOpenAi = false;
        String line;
        String currentEvent = "";
        while ((line = reader.readLine()) != null) {
            if (rawLineCount < 10) {
                rawDump.append(line).append("\n");
                rawLineCount++;
            }
            if (line.isEmpty()) continue;
            if (line.startsWith("event:")) {
                currentEvent = line.substring(6).trim();
                continue;
            }
            String data = sseData(line);
            if (data == null) continue;
            if ("[DONE]".equals(data)) break;

            try {
                JsonNode chunk = objectMapper.readTree(data);
                String type = chunk.path("type").asText("");
                if ("content_block_delta".equals(type) || "content_block_delta".equals(currentEvent)) {
                    JsonNode delta = chunk.path("delta");
                    String dtype = delta.path("type").asText("");
                    if ("text_delta".equals(dtype) || "input_json_delta".equals(dtype)) {
                        String text = delta.path("text").asText("");
                        if (text.isEmpty()) text = delta.path("partial_json").asText("");
                        if (!text.isEmpty()) {
                            fullContent.append(text);
                            try {
                                emitter.send(SseEmitter.event().name("text").data(text));
                            } catch (IOException sendErr) {
                                log.warn("emit anthropic chunk failed: {}", sendErr.toString());
                                return fullContent;
                            }
                        }
                    }
                } else if ("message_stop".equals(type) || "message_stop".equals(currentEvent)) {
                    break;
                } else if ("error".equals(type)) {
                    String msg = chunk.path("error").path("message").asText("Anthropic error");
                    try {
                        emitter.send(SseEmitter.event().name("error").data(msg));
                    } catch (IOException sendErr) {
                        log.warn("emit anthropic error failed: {}", sendErr.toString());
                    }
                    break;
                } else {
                    // 中转站可能声明 anthropic 但实际返回 OpenAI SSE 格式，回退解析
                    JsonNode choices = chunk.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        String delta = choices.path(0).path("delta").path("content").asText("");
                        if (!delta.isEmpty()) {
                            if (!detectedOpenAi) {
                                detectedOpenAi = true;
                                log.warn("[LLM-stream] 配置为 anthropic 但流式响应为 OpenAI 格式，已自动回退；建议改为 protocol: openai");
                            }
                            fullContent.append(delta);
                            try {
                                emitter.send(SseEmitter.event().name("text").data(delta));
                            } catch (IOException sendErr) {
                                log.warn("emit openai-fallback chunk failed: {}", sendErr.toString());
                                return fullContent;
                            }
                        }
                    }
                }
            } catch (IOException parseErr) {
                log.warn("anthropic chunk parse failed: {}", parseErr.toString());
            }
        }
        if (fullContent.length() == 0) {
            String raw = rawDump.toString();
            if (raw.contains("<!doctype html>") || raw.contains("<!DOCTYPE html>") || raw.contains("<html")) {
                log.error("[LLM-stream] anthropic 端点返回 HTML 页面而非 SSE 流，请检查 base-url 是否缺少 /v1 路径前缀");
            }
            log.warn("[LLM-stream] anthropic 流为空 已读 {} 行 原始前 10 行 dump:\n{}", rawLineCount, raw);
        }
        return fullContent;
    }

    /** 根据协议类型分派到对应的解析方法。 */
    public StringBuilder parse(BufferedReader reader, SseEmitter emitter, boolean anthropic) throws IOException {
        return anthropic ? parseAnthropic(reader, emitter) : parseOpenAI(reader, emitter);
    }

    /**
     * 取 SSE data 行的载荷；非 data 行返回 null。
     * <p>SSE 规范中字段冒号后的空格是可选的，部分中转站/代理发 {@code data:{...}}（无空格），
     * 只认 {@code "data: "} 会把整个流静默丢弃。
     */
    private static String sseData(String line) {
        if (!line.startsWith("data:")) return null;
        String d = line.substring(5);
        return d.startsWith(" ") ? d.substring(1) : d;
    }
}

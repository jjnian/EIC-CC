package com.tuiyan.backend.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * 单次 SSE 会话的进度推送器：包装一个 {@link SseEmitter}，统一处理 step / 文本 / 错误事件的发送与
 * "emitter 已关闭" 的容错，避免业务代码到处重复 try-catch。
 * <p>每个请求 new 一个，不是 Spring bean。
 */
public class ChatStepEmitter {

    private static final Logger log = LoggerFactory.getLogger(ChatStepEmitter.class);

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;

    public ChatStepEmitter(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    /**
     * 推送一条 step 事件。
     * @return true=发送成功；false=emitter 已关闭(超时/客户端断开)或发送失败，调用方应停止后续推送。
     */
    public boolean step(String key, String label) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("key", key, "label", label));
            emitter.send(SseEmitter.event().name("step").data(json));
            return true;
        } catch (IllegalStateException closed) {
            // emitter 已 complete(常见于超时或客户端断开)：再 send 会抛此异常。
            // 不再当作错误刷屏，仅 debug 记录，并让调用方据返回值提前收尾。
            log.debug("emit step '{}' skipped, emitter closed: {}", key, closed.toString());
            return false;
        } catch (IOException e) {
            log.warn("emit step '{}' failed (client disconnected?): {}", key, e.toString());
            return false;
        }
    }

    /** 发送任意命名事件（text / error / complete 等）；失败仅记日志。 */
    public void send(String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            log.warn("emit '{}' event failed", event, e);
        }
    }

    /** 安静地 sleep，保留中断标志；用于构建步骤间制造节奏。 */
    public static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

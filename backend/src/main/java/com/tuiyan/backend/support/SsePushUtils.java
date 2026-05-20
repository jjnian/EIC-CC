package com.tuiyan.backend.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SseEmitter 装配与安全发送工具。
 * 提供统一的超时/异常处理 + cancelled 标志的 safeSend。
 */
public final class SsePushUtils {

    private static final Logger log = LoggerFactory.getLogger(SsePushUtils.class);

    private SsePushUtils() {}

    /**
     * 创建一个带超时兜底的 emitter:
     * - timeoutMs 超时 → 发送 name=error,data=timeoutMessage,然后 complete
     * - onCompletion / onError 时静默
     */
    public static SseEmitter newEmitter(long timeoutMs, String timeoutMessage) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitter.onTimeout(() -> {
            try {
                emitter.send(SseEmitter.event().name("error").data(timeoutMessage));
            } catch (IOException e) {
                log.warn("emit timeout-message failed: {}", e.toString());
            }
            emitter.complete();
        });
        emitter.onError(t -> log.warn("emitter error: {}", t.toString()));
        return emitter;
    }

    /**
     * 默认超时文案版本。
     */
    public static SseEmitter newEmitter(long timeoutMs) {
        return newEmitter(timeoutMs, "LLM 响应超时(>" + (timeoutMs / 1000) + "s),请检查 LLM 配置或网络后重试");
    }

    /**
     * 安全发送:cancelled 已置位时跳过;send 抛错时记日志并将 cancelled 置位,返回 false。
     */
    public static boolean safeSend(SseEmitter emitter, AtomicBoolean cancelled, String event, String data) {
        if (cancelled.get()) return false;
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IllegalStateException stateErr) {
            log.warn("emitter already closed when sending {}: {}", event, stateErr.toString());
            cancelled.set(true);
            return false;
        } catch (IOException ioErr) {
            log.warn("emit {} failed (client disconnected?): {}", event, ioErr.toString());
            cancelled.set(true);
            return false;
        }
    }
}

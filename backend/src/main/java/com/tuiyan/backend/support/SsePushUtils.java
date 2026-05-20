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

    /** emitter + 与之绑定的 cancelled 标志(同时设置 onTimeout/onCompletion/onError)。 */
    public record CancellableEmitter(SseEmitter emitter, AtomicBoolean cancelled) {}

    /**
     * 创建一个带超时兜底 + 取消标志的 emitter。
     * 在 controller 同步线程内一次性装好所有回调,避免与异步业务线程之间的注册时序窗口。
     *
     * 行为:
     * - timeoutMs 超时 → 先置 cancelled,再发 error 事件,最后 complete
     * - 客户端断开 / emitter 关闭 → cancelled 置位
     */
    public static CancellableEmitter newCancellableEmitter(long timeoutMs, String timeoutMessage) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onTimeout(() -> {
            cancelled.set(true);
            try {
                emitter.send(SseEmitter.event().name("error").data(timeoutMessage));
            } catch (IOException e) {
                log.warn("emit timeout-message failed: {}", e.toString());
            }
            emitter.complete();
        });
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onError(t -> {
            cancelled.set(true);
            log.warn("emitter error: {}", t.toString());
        });
        return new CancellableEmitter(emitter, cancelled);
    }

    /** 默认超时文案版本。 */
    public static CancellableEmitter newCancellableEmitter(long timeoutMs) {
        return newCancellableEmitter(timeoutMs,
                "LLM 响应超时(>" + (timeoutMs / 1000) + "s),请检查 LLM 配置或网络后重试");
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

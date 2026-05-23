package com.tuiyan.backend.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM 调用统计服务：进程内累计调用次数、错误数、总耗时，以及按模型名分组的统计。
 * <p>全部存于内存，进程重启即清零；通过 {@code /api/system/llm-metrics} 暴露给前端。
 * 使用 {@link AtomicLong} + {@link ConcurrentHashMap} 是因为可能从多个推演线程并发记账。
 */
@Service
public class LlmMetricsService {

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    // 按模型名分组的调用计数 / 错误计数；computeIfAbsent 保证首次写入线程安全
    private final ConcurrentHashMap<String, AtomicLong> callsByModel = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> errorsByModel = new ConcurrentHashMap<>();

    /**
     * 记录一次 LLM 调用结果。
     * <p>由 {@link LlmService} 在每次 HTTP 调用结束（成功或失败）后调用一次。
     * @param modelName 模型名称（来自 ModelEntry.modelName）
     * @param latencyMs 调用耗时（毫秒）
     * @param success 是否成功；失败时同时累加 totalErrors 与按模型分组的错误数
     */
    public void recordCall(String modelName, long latencyMs, boolean success) {
        totalCalls.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);
        callsByModel.computeIfAbsent(modelName, k -> new AtomicLong()).incrementAndGet();
        if (!success) {
            totalErrors.incrementAndGet();
            errorsByModel.computeIfAbsent(modelName, k -> new AtomicLong()).incrementAndGet();
        }
    }

    /**
     * 获取当前统计快照（非原子，仅指标展示用，允许微小不一致）。
     * <p>avgLatencyMs 在 calls=0 时返回 0，避免除零。
     */
    public Map<String, Object> getStats() {
        long calls = totalCalls.get();
        long avgLatency = calls > 0 ? totalLatencyMs.get() / calls : 0;

        List<Map<String, Object>> modelStats = callsByModel.entrySet().stream()
            .map(e -> Map.<String, Object>of(
                "model", e.getKey(),
                "calls", e.getValue().get(),
                "errors", errorsByModel.getOrDefault(e.getKey(), new AtomicLong()).get()
            ))
            .collect(Collectors.toList());

        return Map.of(
            "totalCalls", calls,
            "totalErrors", totalErrors.get(),
            "avgLatencyMs", avgLatency,
            "byModel", modelStats
        );
    }

    /** 重置所有统计数据。运维 / 调试用，不暴露给普通用户。 */
    public void reset() {
        totalCalls.set(0);
        totalErrors.set(0);
        totalLatencyMs.set(0);
        callsByModel.clear();
        errorsByModel.clear();
    }
}

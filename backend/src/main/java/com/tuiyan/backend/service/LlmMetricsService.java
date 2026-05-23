package com.tuiyan.backend.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM 调用统计服务：记录总调用次数、错误次数、延迟，以及按模型的分组统计。
 */
@Service
public class LlmMetricsService {

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> callsByModel = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> errorsByModel = new ConcurrentHashMap<>();

    /**
     * 记录一次 LLM 调用结果
     * @param modelName 模型名称
     * @param latencyMs 调用耗时（毫秒）
     * @param success 是否成功
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
     * 获取当前统计快照
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

    /**
     * 重置所有统计数据
     */
    public void reset() {
        totalCalls.set(0);
        totalErrors.set(0);
        totalLatencyMs.set(0);
        callsByModel.clear();
        errorsByModel.clear();
    }
}

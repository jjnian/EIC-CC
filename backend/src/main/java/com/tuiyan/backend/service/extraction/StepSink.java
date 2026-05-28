package com.tuiyan.backend.service.extraction;

/** 抽取进度回调：把每个阶段以 (key,label) 形式上报给调用方（SSE 推送 / 或 no-op）。 */
@FunctionalInterface
public interface StepSink {
    void emit(String key, String label);

    /** 非流式调用使用的空进度回调。 */
    StepSink NOOP = (k, l) -> { };
}

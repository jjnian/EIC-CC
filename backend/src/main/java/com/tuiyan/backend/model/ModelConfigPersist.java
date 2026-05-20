package com.tuiyan.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Jackson mixin：在持久化场景下重新暴露 apiKey 字段，覆盖 ModelConfig 自身的 WRITE_ONLY 标注。
 * 仅供 LlmService 内部的 persistMapper 使用，外部 HTTP 序列化仍走默认 mapper（不会泄露 apiKey）。
 */
public abstract class ModelConfigPersist {
    @JsonProperty
    public abstract String getApiKey();
}

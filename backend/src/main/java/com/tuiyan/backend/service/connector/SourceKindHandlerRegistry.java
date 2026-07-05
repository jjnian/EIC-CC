package com.tuiyan.backend.service.connector;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据源类型处理器注册表:注入全部 {@link SourceKindHandler},按 kind 解析。
 * 加一种数据源只需新增一个 {@code @Component SourceKindHandler},自动被收录。
 */
@Component
public class SourceKindHandlerRegistry {

    private final List<SourceKindHandler> handlers;

    public SourceKindHandlerRegistry(List<SourceKindHandler> handlers) {
        this.handlers = handlers;
    }

    /** 解析该 kind 的处理器;无匹配返回 null(调用方按业务决定兜底)。 */
    public SourceKindHandler resolve(String kind) {
        for (SourceKindHandler h : handlers) {
            if (h.supports(kind)) return h;
        }
        return null;
    }
}

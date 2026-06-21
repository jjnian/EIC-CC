package com.tuiyan.backend.service.connector;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 方言注册表：注入全部 {@link SqlDialect} 实现，按 kind 解析。
 * <p>新增方言只要是 {@code @Component}，Spring 会自动注入到这里，无需改动本类。
 */
@Component
public class SqlDialectRegistry {

    private final List<SqlDialect> dialects;

    public SqlDialectRegistry(List<SqlDialect> dialects) {
        this.dialects = dialects;
    }

    /** 解析 kind 对应的方言；找不到抛 400（IllegalArgumentException）。 */
    public SqlDialect resolve(String kind) {
        return dialects.stream()
                .filter(d -> d.supports(kind))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的数据库类型: " + kind));
    }
}

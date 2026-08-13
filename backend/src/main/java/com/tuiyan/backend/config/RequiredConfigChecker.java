package com.tuiyan.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 启动期必需配置校验：对「忘配会导致静默故障或弱口令连库」的关键项做 fail-fast，
 * 缺失则抛异常让应用启动失败，而不是带隐患跑起来。
 * <p>当前校验项：
 * <ul>
 *   <li>{@code spring.datasource.password}（{@code DB_PASSWORD}）：为空则拒绝启动。
 *       历史上曾把默认值设为 {@code 123456}，生产忘配环境变量就会用弱口令连库，已改为留空 + 此处强校验。</li>
 * </ul>
 * <p>本地开发需在环境变量或 IDE 启动配置里设置 {@code DB_PASSWORD}（值随意，与本地 Pg 实例一致即可）。
 */
@Configuration
public class RequiredConfigChecker {

    private static final Logger log = LoggerFactory.getLogger(RequiredConfigChecker.class);

    private final DataSourceProperties dsProps;

    public RequiredConfigChecker(DataSourceProperties dsProps) {
        this.dsProps = dsProps;
    }

    @PostConstruct
    public void validate() {
        requireNonBlank("DB_PASSWORD (spring.datasource.password)", dsProps.getPassword());
        log.info("[Config] 必需配置校验通过：db-url={}", dsProps.getUrl());
    }

    private static void requireNonBlank(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "启动校验失败：缺少必需配置 " + name + "。请在环境变量或 application.yml 中设置后重启。");
        }
    }
}

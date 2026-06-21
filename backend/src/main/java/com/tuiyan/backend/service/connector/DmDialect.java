package com.tuiyan.backend.service.connector;

import org.springframework.stereotype.Component;

/**
 * 达梦 DM8 方言：兼容 Oracle 数据字典(USER_*)，复用 {@link OracleDialect} 的内省与方言规则，
 * 仅 kind、默认端口、JDBC URL/驱动不同。内省结果 kind 由父类 {@link #build} 自动回填为 dm。
 */
@Component
public class DmDialect extends OracleDialect {

    @Override
    public String kind() {
        return SourceKind.DM;
    }

    @Override
    public int defaultPort() {
        return 5236;
    }

    @Override
    public String jdbcUrl(String host, int port, String database, String params) {
        // 达梦 DM8：jdbc:dm://host:port，登录用户即默认 schema（database 字段留空亦可）。
        return "jdbc:dm://" + host + ":" + port + (params == null || params.isBlank() ? "" : "?" + params);
    }

    @Override
    public String driverClass() {
        return "dm.jdbc.driver.DmDriver";
    }
}

package com.tuiyan.backend.service.connector;

import org.springframework.stereotype.Component;

/**
 * GBase 8a 方言：走 MySQL 线协议，复用 {@link MysqlDialect} 的驱动、URL 加固、内省与方言规则，
 * 仅 kind 与默认端口不同。内省结果的 kind 由父类 {@link #build} 取 {@link #kind()} 自动回填为 gbase。
 */
@Component
public class GbaseDialect extends MysqlDialect {

    @Override
    public String kind() {
        return SourceKind.GBASE;
    }

    @Override
    public int defaultPort() {
        return 5258;
    }
}

package com.tuiyan.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 从 {@code application.yml} 中 {@code spring.datasource.*} 读取数据源连接配置。
 */
@Component
@ConfigurationProperties(prefix = "spring.datasource")
public class DataSourceProperties {

    private String url = "";
    private String password = "";

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}

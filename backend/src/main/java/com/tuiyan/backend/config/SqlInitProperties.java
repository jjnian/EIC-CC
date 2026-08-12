package com.tuiyan.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 从 {@code application.yml} 中 {@code spring.sql.init.*} 读取 SQL 初始化配置。
 */
@Component
@ConfigurationProperties(prefix = "spring.sql.init")
public class SqlInitProperties {

    private boolean enabled = true;
    private String mode = "embedded";
    private String schemaLocations = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getSchemaLocations() { return schemaLocations; }
    public void setSchemaLocations(String schemaLocations) { this.schemaLocations = schemaLocations; }
}

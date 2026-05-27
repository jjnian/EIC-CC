package com.tuiyan.backend.model.dto;

import java.util.Map;

/** 创建活数据源请求：name + kind + config（具体字段按 kind 不同，原样存为 config_json）。 */
public class DataSourceCreateRequest {
    private String name;
    private String kind;
    private Map<String, Object> config;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
}

package com.tuiyan.backend.model.dto;

import java.util.Map;

/** 编辑活数据源：name 可改；config 可改（kind 不可变）。 */
public class DataSourceUpdateRequest {
    private String name;
    private Map<String, Object> config;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
}

package com.tuiyan.backend.model;

public class ConfigResponse {
    private String baseUrl;
    private String modelName;

    public ConfigResponse() {}
    public ConfigResponse(String baseUrl, String modelName) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
}

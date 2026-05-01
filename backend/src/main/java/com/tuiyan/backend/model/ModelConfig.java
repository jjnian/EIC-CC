package com.tuiyan.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

public class ModelConfig {
    private String id;
    private String name;
    private String baseUrl;
    private String modelName;
    private String apiKey;
    private boolean enabled;
    private long createdAt;
    private long updatedAt;

    public ModelConfig() {}

    public ModelConfig(String name, String baseUrl, String modelName, String apiKey) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.apiKey = apiKey;
        this.enabled = true;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @JsonIgnore
    public String getApiKeyForSerialization() { return null; }
}

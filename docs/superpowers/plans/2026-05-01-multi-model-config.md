# 多模型配置管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在设置页面添加自定义大模型配置的增删改查功能，并在聊天面板中支持选择不同模型进行对话。

**Architecture:** 后端将 `llm-config.json`（单一配置）扩展为 `llm-models.json`（配置数组），新增 `ModelConfig` 数据模型和 CRUD REST API。前端设置页面从表单改为卡片列表 + 模态框，聊天面板从预设模型下拉改为所有模型分组选择器。

**Tech Stack:** Spring Boot 3 + Java 17 + Vue 3 + TypeScript + Vite

---

## 文件结构总览

### 新创建文件
- `backend/src/main/java/com/tuiyan/backend/model/ModelConfig.java` — 单个模型配置的数据模型
- `backend/src/main/java/com/tuiyan/backend/controller/ModelController.java` — 模型配置的 CRUD REST API

### 修改文件
- `backend/src/main/java/com/tuiyan/backend/model/ConfigResponse.java` — 新增 `customModels` 字段和 `ModelConfigInfo` 内部类
- `backend/src/main/java/com/tuiyan/backend/model/ConfigRequest.java` — 新增 `ModelConfigRequest` 嵌套类用于创建/更新
- `backend/src/main/java/com/tuiyan/backend/service/LlmService.java` — 新增 CRUD 方法，修改 `chat()` 支持 `configId`
- `backend/src/main/resources/llm-models.json` — 新建配置文件（从 llm-config.json 迁移）
- `frontend/src/components/SettingsView.vue` — 从表单改为卡片列表 + 模态框
- `frontend/src/components/ChatPanel.vue` — 模型选择器分组展示预设 + 自定义模型

---

## Task 1: 创建 ModelConfig 数据模型

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/model/ModelConfig.java`

- [ ] **Step 1: 创建 ModelConfig.java**

```java
package com.tuiyan.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
        this.id = java.util.UUID.randomUUID().toString();
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

    /**
     * 序列化时隐藏 API Key
     */
    @JsonIgnore
    public String getApiKeyForSerialization() {
        return null;
    }
}
```

- [ ] **Step 2: 验证 ModelConfig 能被 Jackson 正确序列化/反序列化**

在 `LlmService.java` 的 `getConfig()` 方法中临时加一段测试代码（完成后删除）：

```java
// 临时验证
ObjectMapper mapper = new ObjectMapper();
ModelConfig test = new ModelConfig("test", "http://test/v1", "test-model", "key-123");
String json = mapper.writeValueAsString(test);
System.out.println("Serialized (no apiKey): " + json);
assert !json.contains("apiKey") : "apiKey should be hidden";
```

运行验证后删除测试代码。

- [ ] **Step 3: 确认文件存在**

```bash
ls backend/src/main/java/com/tuiyan/backend/model/ModelConfig.java
```

Expected: File exists with no errors.

---

## Task 2: 迁移配置存储格式

**Files:**
- Create: `backend/src/main/resources/llm-models.json`
- Modify: `backend/src/main/java/com/tuiyan/backend/service/LlmService.java`

- [ ] **Step 1: 创建 llm-models.json（从 llm-config.json 迁移）**

读取现有的 `llm-config.json`：

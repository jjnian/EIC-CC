# 多模型配置管理设计

## 日期
2026-05-01

## 目标
在设置页面添加自定义大模型配置功能，允许用户添加任意数量的自定义模型配置，并在聊天面板中选择不同的模型进行对话。

## 方案选择
采用**方案 A：简单扩展** — 将 `llm-config.json` 改为配置数组，后端提供 CRUD 接口，前端通过下拉选择器展示所有可用模型。

---

## 架构设计

### 后端改造

#### 1. 新增 `ModelConfig.java`
单个模型配置的数据模型，包含：
- `id` (String) - 唯一标识，UUID
- `name` (String) - 显示名称，如"我的自定义模型"
- `baseUrl` (String) - API 地址
- `modelName` (String) - 模型名称
- `apiKey` (String) - API Key（加密存储）
- `enabled` (Boolean) - 是否启用
- `createdAt` (Long) - 创建时间戳
- `updatedAt` (Long) - 更新时间戳

#### 2. 修改 `LlmService.java`
**职责变更：**
- 从单一配置管理改为多模型配置管理
- 新增方法：
  - `getAllModelConfigs()` - 获取所有模型配置列表
  - `createModelConfig(ModelConfig)` - 创建新模型配置
  - `updateModelConfig(String id, ModelConfig)` - 更新模型配置
  - `deleteModelConfig(String id)` - 删除模型配置
  - `enableModelConfig(String id)` - 启用/禁用模型
- 修改 `chat()` 方法支持按配置 ID 调用

**配置文件变更：**
- 从 `llm-config.json`（单一对象）改为 `llm-models.json`（对象数组）
- 保留向后兼容：如果旧文件存在，自动迁移为数组中的第一个元素

#### 3. 修改 `ConfigController.java`
**新增接口：**
- `GET /api/models` - 获取所有模型配置列表（不包含 API Key）
- `POST /api/models` - 创建新模型配置
- `PUT /api/models/{id}` - 更新模型配置
- `DELETE /api/models/{id}` - 删除模型配置
- `PATCH /api/models/{id}/enable` - 切换启用状态

**保留接口：**
- `GET /api/config` - 返回当前活跃配置 + 预设提供商列表（向后兼容）
- `POST /api/config` - 保存默认配置（向后兼容）

#### 4. 修改 `ConfigResponse.java`
新增字段：
- `List<ModelConfigInfo> customModels` - 自定义模型列表
- `ModelConfigInfo` 内部类包含：id, name, baseUrl, modelName, enabled

### 前端改造

#### 1. 重写 `SettingsView.vue`
**布局变更：**
- 从"单一配置表单"改为"模型卡片列表 + 新增按钮"
- 列表形式垂直排列，每个卡片显示：
  - 模型名称（标题）
  - Base URL（灰色文本）
  - 模型名（灰色文本）
  - 状态标签（已启用/已禁用）
  - 操作按钮（编辑、删除、切换启用状态）

**交互流程：**
1. 点击"新增"按钮 → 弹出模态框 → 填写配置 → 保存
2. 点击"编辑"按钮 → 弹出模态框（预填充）→ 修改 → 保存
3. 点击"删除"按钮 → 确认对话框 → 删除
4. 点击"切换状态"按钮 → 切换启用/禁用

**模态框表单字段：**
- 模型名称（必填）
- Base URL（必填）
- 模型名称（必填）
- API Key（必填，密码输入框）

#### 2. 增强 `ChatPanel.vue`
**模型选择器变更：**
- 从"预设模型下拉列表"改为"所有模型下拉列表"
- 分组展示：
  - "预设模型" - 来自 LlmProvider 枚举
  - "自定义模型" - 来自 `/api/models` 接口
- 只显示已启用的自定义模型
- 选择后调用 `/api/chat` 时携带 `modelOverride` 和 `configId`

---

## 数据流

```
设置页面：
  用户填写表单 → POST /api/models → 保存到 llm-models.json → 刷新列表

聊天面板：
  页面加载 → GET /api/models → 渲染下拉列表 → 选择模型 → 发送请求时携带 configId
  请求处理 → LlmService 按 configId 查找配置 → 使用对应的 baseUrl/apiKey/modelName 调用 API
```

---

## 错误处理

### 后端
- 配置不存在 → 404 Not Found
- 参数校验失败 → 400 Bad Request + 错误详情
- 文件读写失败 → 500 Internal Server Error
- API Key 为空 → 400 Bad Request

### 前端
- 网络请求失败 → Toast 提示 + 重试按钮
- 表单验证失败 → 行内错误提示
- 删除确认 → 模态框二次确认

---

## 测试策略

### 后端
- 单元测试：ModelConfig 创建/验证
- 集成测试：CRUD 接口完整流程
- 边界测试：空数组、重复名称、无效 URL

### 前端
- 组件测试：卡片渲染、模态框交互
- E2E 测试：完整添加-编辑-删除流程
- 集成测试：模型选择器与聊天功能联动

---

## 安全考虑

1. **API Key 存储** - 使用 Jasypt 或 Spring Vault 加密存储
2. **API Key 传输** - 仅在创建/更新时传输，列表接口不返回 API Key
3. **输入校验** - Base URL 必须是合法 URL，防止 SSRF
4. **访问控制** - 暂不实现，后续可加 JWT 认证

---

## 未来扩展

1. **模型测试** - 添加"测试连接"按钮，验证配置是否可用
2. **使用统计** - 记录每个模型的调用次数和 token 消耗
3. **预设模板** - 提供常用模型的快速配置模板
4. **批量导入** - 支持从 JSON 文件批量导入配置

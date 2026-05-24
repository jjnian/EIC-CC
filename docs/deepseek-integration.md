# DeepSeek 大模型接入指南

> 适用于本仓库 `backend/` 的 Spring Boot 后端。基于 [DeepSeek 官方文档](https://api-docs.deepseek.com/)（2026-05 版本）+ 本项目 `LlmService` 现有实现整理。

---

## 1. 模型清单（2026-05 现行）

| Model ID            | 适用场景                          | Context Window | 单次最大输出 | 思考模式 | 工具调用 | 视觉 |
| ------------------- | --------------------------------- | -------------- | ------------ | -------- | -------- | ---- |
| `deepseek-v4-flash` | 默认主力，速度优先                | 128K           | 8192         | 可开关   | ✅       | ❌   |
| `deepseek-v4-pro`   | 复杂推理 / 长 JSON / 工具规划     | 128K           | 8192         | 可开关   | ✅       | ❌   |
| `deepseek-chat`     | **已弃用**，2026-07-24 下线       | —              | —            | —        | —        | —    |
| `deepseek-reasoner` | **已弃用**，2026-07-24 下线       | —              | —            | —        | —        | —    |

> `deepseek-chat` / `deepseek-reasoner` 在过渡期分别指向 `deepseek-v4-flash` 的非思考 / 思考模式，**新接入直接用 v4 系列**。

> 单次最大输出：官方"商品/接入"文档稳定值是 8192 tokens。本仓库常出现 26000+ 字符的超长 JSON（≈8000+ tokens），刚好顶到上限。**这就是上传 Word 文档抽本体时 `Unexpected end-of-input within/between Array entries` 的根因。**

---

## 2. 协议与端点

DeepSeek 完全兼容 OpenAI Chat Completions 协议：

| 项目        | 值                                              |
| ----------- | ----------------------------------------------- |
| Base URL    | `https://api.deepseek.com`（推荐）              |
| Beta Base   | `https://api.deepseek.com/beta`（前置实验特性） |
| 完整端点    | `POST /v1/chat/completions`                     |
| 列模型      | `GET /v1/models`                                |
| 认证        | HTTP Header: `Authorization: Bearer <API_KEY>`  |
| Content-Type | `application/json`                             |

API Key 在 [platform.deepseek.com](https://platform.deepseek.com) 自助申请。

---

## 3. 在本项目中的接入方式

本项目 LLM 调用全部走 `backend/src/main/java/com/tuiyan/backend/service/LlmService.java`，DeepSeek 走 OpenAI 兼容分支（`buildOpenAiBody` + `streamOpenAI`）。

### 3.1 application.yml 配置

```yaml
app:
  llm:
    models:
      - id: deepseek-flash              # 项目内引用 id（任意自定义）
        name: DeepSeek V4 Flash
        provider: deepseek
        base-url: https://api.deepseek.com
        model-name: deepseek-v4-flash   # 必须是官方 model id
        api-key: ${DEEPSEEK_API_KEY:sk-xxxxxxxxxxxx}
        protocol: openai                # 路由到 OpenAI 兼容分支
        enabled: true
        context-window: 128000
        max-output-tokens: 8192         # ⚠️ 上限就是 8192，再大上游会拒
        capabilities:
          - streaming
          - json
          - tools

      - id: deepseek-pro
        name: DeepSeek V4 Pro
        provider: deepseek
        base-url: https://api.deepseek.com
        model-name: deepseek-v4-pro
        api-key: ${DEEPSEEK_API_KEY:sk-xxxxxxxxxxxx}
        protocol: openai
        enabled: true
        context-window: 128000
        max-output-tokens: 8192
        capabilities:
          - streaming
          - json
          - tools
```

**字段对应代码**：见 `backend/src/main/java/com/tuiyan/backend/config/LlmProperties.java:29-69`，`max-output-tokens` 已被 `LlmService.resolveConfig` 读出注入到 `ResolvedConfig.maxOutputTokens`，再透传给 `buildOpenAiBody` 写入请求 `max_tokens`（修复 `LlmService.java:606-615`）。

### 3.2 推荐 API Key 注入方式

不要把 key 硬写进 yml 提交到 git。三种方式优先级递减：

```bash
# 方式 1：环境变量（推荐）
setx DEEPSEEK_API_KEY "sk-xxxxxxxx"   # Windows
export DEEPSEEK_API_KEY="sk-xxxxxxxx" # macOS/Linux

# 方式 2：通用环境变量（覆盖所有模型）
set LLM_API_KEY=sk-xxxxxxxx

# 方式 3：直接写 yml（仅本机调试用）
api-key: sk-xxxxxxxx
```

`LlmService.resolveConfig` 同时支持 `LLM_BASE_URL` / `LLM_MODEL_NAME` / `LLM_API_KEY` 兜底（`LlmService.java:567-574`）。

---

## 4. /chat/completions 关键参数速查

| 参数                | 类型                | 说明                                                                     |
| ------------------- | ------------------- | ------------------------------------------------------------------------ |
| `model`             | string              | 必填，`deepseek-v4-flash` / `deepseek-v4-pro`                            |
| `messages`          | array               | 必填，`role`：`system` / `user` / `assistant` / `tool`                   |
| `max_tokens`        | int                 | 输出 token 上限，**不传时默认 4096**，最大 8192。本项目永远显式传        |
| `stream`            | bool                | 流式返回 SSE                                                             |
| `stream_options.include_usage` | bool     | 流式时让最后一个 chunk 携带 usage（费用统计用）                          |
| `temperature`       | number              | 0~2，默认 1。**JSON 抽取建议 0.1**，对话建议 0.7                         |
| `top_p`             | number              | 0~1，默认 1                                                              |
| `response_format`   | object              | `{"type": "json_object"}` 强制 JSON 输出                                 |
| `tools`             | array               | OpenAI 风格 function calling                                             |
| `tool_choice`       | string\|object      | `auto` / `none` / 指定函数                                               |
| `stop`              | string\|array       | 触发停止的序列                                                           |
| `frequency_penalty` | number              | -2~2                                                                     |
| `presence_penalty`  | number              | -2~2                                                                     |
| `logprobs`          | bool                | 返回每 token 概率                                                        |
| `user_id`           | string              | 安全审计 + KVCache 隔离                                                  |

### 思考模式（V4 Pro 推荐）

```jsonc
{
  "model": "deepseek-v4-pro",
  "thinking": {
    "type": "enabled",          // disabled 关闭思考
    "reasoning_effort": "high"  // high | max
  },
  "messages": [...]
}
```

返回的 `choices[0].message.reasoning_content` 是思考链，正式答案在 `content`。**多轮对话回传 assistant 消息时必须带上完整 `reasoning_content`**，否则模型会丢失思考上下文。

---

## 5. 请求 / 响应示例

### 5.1 普通对话

```bash
curl https://api.deepseek.com/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY" \
  -d '{
    "model": "deepseek-v4-flash",
    "messages": [
      {"role": "system", "content": "你是简洁的助手"},
      {"role": "user", "content": "你好"}
    ],
    "max_tokens": 1024
  }'
```

### 5.2 强制 JSON（本项目本体抽取使用）

```jsonc
{
  "model": "deepseek-v4-pro",
  "messages": [
    {"role": "system", "content": "你是本体抽取器，只输出 JSON"},
    {"role": "user", "content": "..."}
  ],
  "response_format": {"type": "json_object"},
  "max_tokens": 8192,
  "temperature": 0.1
}
```

> ⚠️ 用 `json_object` 时，**system 或 user 中必须出现 "json" 字样**，否则上游会拒。

### 5.3 流式 SSE（本项目 `chatStreaming` 路径）

请求加 `"stream": true`，响应是：

```
data: {"id":"...","choices":[{"delta":{"content":"你"},"index":0}]}
data: {"id":"...","choices":[{"delta":{"content":"好"},"index":0}]}
data: {"id":"...","choices":[{"delta":{},"finish_reason":"stop","index":0}]}
data: [DONE]
```

每行 `data:` 后是 JSON chunk，文本增量在 `choices[0].delta.content`，`[DONE]` 标志结束。本项目 `LlmService.streamOpenAI`（`LlmService.java:849-872`）就是按此规则累积。

### 5.4 响应中的 Usage 统计

```jsonc
{
  "usage": {
    "prompt_tokens": 1024,
    "prompt_cache_hit_tokens": 768,   // 命中磁盘缓存（便宜 10x）
    "prompt_cache_miss_tokens": 256,
    "completion_tokens": 320,
    "total_tokens": 1344
  }
}
```

---

## 6. 上下文磁盘缓存（自动开启，免费提速）

DeepSeek 自动把最近的 prompt 前缀缓存到磁盘。**无需任何参数，自然命中**。

- 命中部分：0.1 元/百万 tokens（约官价 1/10）
- 未命中部分：常规价
- 命中条件：prompt 前缀逐字节相同（连 system prompt 也算前缀）

**项目优化建议**：
- `LlmService.SYSTEM_INSTRUCTION` / `EXTRACT_SYSTEM` 等常量保持稳定字符串，每次请求前缀复用
- 长图谱上下文放在 user message 前段，让"图谱描述"部分尽量复用
- 不要在 system 里塞时间戳、随机 id，否则缓存全失效

可在响应 `usage` 里查看命中率。

---

## 7. 错误码与排查

| HTTP | 含义              | 处理                                                       |
| ---- | ----------------- | ---------------------------------------------------------- |
| 400  | 参数错            | 看响应 body，常见：`json_object` 模式但 prompt 没出现 json |
| 401  | API Key 无效      | 检查 Authorization header                                  |
| 402  | 余额不足          | 充值                                                       |
| 422  | 参数语义错        | 例如 `max_tokens` 超过 8192                                |
| 429  | 频控              | 退避重试，本项目 `sendHttp` 没有自动重试，调用方自己加     |
| 500/503 | 服务繁忙       | 退避重试                                                   |

`finish_reason` 含义：

- `stop`：正常结束
- `length`：**碰到 `max_tokens` 上限被截断** ← 本项目历史 bug 的祸根
- `content_filter`：被安全策略拦截
- `tool_calls`：模型选择调用工具
- `insufficient_system_resource`：上游临时资源不足

---

## 8. 项目内现有的本体抽取流（供参考排错）

```
用户上传 docx
    └── 前端 useAttachments.ts:42  POST /api/extract/docx-text  → 纯文本
        └── 文本随消息进 ChatPanel
            └── POST /api/chat (Accept: text/event-stream)
                └── LlmService.chatStreaming
                    └── buildOpenAiBody (含 max_tokens=cfg.maxOutputTokens)
                    └── DeepSeek SSE
                    └── streamOpenAI 累积 → readTree
                    └── SSE complete 事件回前端
```

如果要避免 8192 token 上限，应改走 `/api/ontology-models/extract`（`LlmService.extractOntologyFromSources`，已自带 30000 字符 chunk 切分 + 按 label 合并），而不是塞进 chat 流。

---

## 9. 切换 / 新增 DeepSeek 模型 Checklist

- [ ] `application.yml` 新增 `app.llm.models[]` 条目
- [ ] `model-name` 用官方 id（不要写自定义短名）
- [ ] `protocol: openai`，确保走 `buildOpenAiBody` 分支
- [ ] `max-output-tokens` 不超过 8192
- [ ] API Key 走环境变量
- [ ] 重启 Spring Boot
- [ ] 前端在设置页选择新模型，发条短消息验证连通性
- [ ] 观察后端 INFO 日志 `[LLM-stream] 流式完成 总耗时=... 响应长度=... chars`，确认 `finish_reason` 不是 `length`

---

## 参考链接

- 官方 API 文档：https://api-docs.deepseek.com/
- 创建 Chat Completion：https://api-docs.deepseek.com/api/create-chat-completion
- 列模型：https://api-docs.deepseek.com/api/list-models
- 思考模式指南：https://api-docs.deepseek.com/guides/thinking_mode
- 工具调用指南：https://api-docs.deepseek.com/guides/tool_calls
- 上下文缓存：https://api-docs.deepseek.com/guides/kv_cache
- 价格与配额：https://platform.deepseek.com

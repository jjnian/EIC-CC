# Phase 1 推演深化包设计文档

> 编写日期：2026-05-23
> 来源：ROADMAP-v2.md Phase 1 中的 P1-7 / P1-8 / P1-10
> 范围：合并实现单步"为什么？" + 原始 prompt 查看 + 概率约束

---

## 1. 目标与范围

| 项 | 来源 | 说明 |
|---|---|---|
| P1-7 | ROADMAP-v2 §3.4 | 单步"为什么？"——对预测节点请求 LLM 给出依据/假设/反例三段解释 |
| P1-8 | ROADMAP-v2 §3.4 | 原始 prompt 查看（不做 token 统计） |
| P1-10 | ROADMAP-v2 §3.6 | 概率约束（force/block 之外新增 probability 模式） |

**非目标：** token 数 / 成本统计；解释结果与图谱节点的双向跳转；概率约束作用于预测节点（仅作用于现有图谱节点）。

---

## 2. 数据模型扩展

### 2.1 `Constraint.java`

```java
public class Constraint {
    private String nodeId;
    private String mode;          // force | block | probability
    private String note;
    private Double probability;   // 仅 mode=probability 时有效，0..1
}
```

`@JsonInclude(NON_NULL)` 已是项目默认；旧 JSON 文件读取时 `probability` 自动为 null。

### 2.2 `Scenario.java`

```java
public class Scenario {
    // ... 现有字段
    private String rawPrompt;     // P1-8：完整 system + user prompt 文本快照
}
```

### 2.3 `PredictionDag.java`

```java
public class PredictionDag {
    // ... 现有字段
    private Map<String, NodeExplanation> explanations;   // P1-7：按节点 id 缓存
}

public class NodeExplanation {
    private String evidence;           // 依据
    private String assumptions;        // 假设
    private String counterexamples;    // 反例
    private long generatedAt;          // 生成时间戳
    private String modelName;          // 生成所用模型
}
```

兼容性：旧分支 JSON 无这些字段 → null，UI 展示为"未生成"。

---

## 3. 后端 API

### 3.1 P1-8：原始 prompt 通道

**改造 `LlmService.predictChain()`：** 抽取 `PredictPromptBuilder.build(req) → { system, user }` 内部方法，返回结构化数据。`PredictionOrchestrator.run()` 在调用 `predictChain` 时同步构造 prompt 并写入 `Scenario.rawPrompt`（拼接格式：`=== SYSTEM ===\n{system}\n\n=== USER ===\n{user}`）。

**新增端点：**

```
GET /api/scenarios/{id}/raw-prompt
  → 200 { rawPrompt: string | null }
```

按需拉取，避免列表查询带回大字段。

### 3.2 P1-7：详细解释 SSE 端点

```
POST /api/scenarios/{scenarioId}/explain
  body: { nodeId, modelOverride?, configId?, forceRegenerate?: boolean }
  → SSE:
     event: chunk    data: { field: "evidence" | "assumptions" | "counterexamples", text: <增量片段> }
     event: complete data: { explanation: NodeExplanation }
     event: error    data: <错误信息>
```

**Service 实现 `ScenarioExplanationService`：**

1. 加载 Scenario，按 nodeId 找到对应 chain step；找不到 → 抛 `ResourceNotFoundException`
2. 缓存命中（`explanations[nodeId]` 存在 且 `forceRegenerate != true`）→ 直接发 `complete` 事件
3. 拼 prompt：`{step.label} (id={nodeId}, type={step.type})` 在因果链上下文（前 k 步摘要 + triggered_by 节点 + confidence + 相邻 rule_driven 边）中的解释
4. system prompt 固定模板（要求 LLM 返回 JSON `{evidence, assumptions, counterexamples}`，每段 2-4 句中文）
5. 调 `LlmService.streamExplanationJson(...)`：复用现有 OpenAI/Anthropic 流式管线，按 JSON 字段切分推送 chunk
6. 解析失败时容错（关键字切分），仍失败 → SSE error
7. 完成后 `scenario.dag.explanations[nodeId] = explanation`，调 `scenarioService.save(s)` 落盘

### 3.3 P1-10：概率约束的后端处理

**`LlmService.summarizeConstraints()`** 增加 `probability` 分支：

```
- 概率: {label} 先验概率 = 0.70 (probability)。请把先验作为初值，结合上下游证据用贝叶斯式更新；
  返回的 confidence 应反映这个综合后验。
```

**`PredictionOrchestrator.buildStep()`** 计算 `effProb` 时融合用户先验：

```java
// 现有 force/block 集合外，新增 priorMap: nodeId -> probability
Double prior = priorMap.get(linkedExistingNodeId);
if (prior != null) {
    // 先验作为入口节点的 effProb 初值（覆盖 1.0 默认值）
    effProb.putIfAbsent(linkedExistingNodeId, prior);
}
```

**作用域限制：** 概率约束的 `nodeId` 必须是现有图谱节点（否则后端无法定位入口节点的 `effProb`）。前端在添加约束时校验。

---

## 4. 前端改动

### 4.1 P1-10 三态切换（`PredictDialog.vue`）

```ts
type Constraint = {
  nodeId: string;
  mode: 'force' | 'block' | 'probability';
  probability?: number;
};
```

- `pd-cmode` 按钮三态循环：必然 → 禁止 → 概率 → 必然；按钮宽度 44px → 56px
- `mode === 'probability'` 时同行追加 range 滑块 + 数值显示
- `pd-cand-actions` 添加第三个按钮"概率"，点击后默认 `probability=0.5`
- 视觉色调：`probability` 用紫色 `#bb77ff`
- 冲突检测保持 force ↔ block 二态语义（probability 不参与）
- 候选节点检查：若节点 id 以 `p_` / `pe_` 开头（推演节点）→ 禁用"概率"按钮 + tooltip"概率约束仅适用于现有图谱节点"

### 4.2 P1-8 原始 prompt 查看（`PredictionMessage.vue`）

- 头部 `pmsg-head` 在状态非 running 时显示"📜 prompt"按钮
- 点击 → `RawPromptDialog.vue`（新文件）：
  - 全屏遮罩 + 等宽字体展示文本
  - 底部按钮：复制全文 / 关闭
  - `branchId` 缺失时按钮隐藏

### 4.3 P1-7 浮动解释面板

**右键菜单扩展（`GraphCanvas.vue`）：** 节点 `source === 'predicted'` 时新增条目"🔍 为什么会发生？"

**新建 `ExplanationPanel.vue`：**
- 浮动卡片 360x440，初始位置画布右下角，顶部拖拽 + 折叠 + 关闭
- 头部：节点 label + type + confidence 徽章
- 三段卡片：依据 / 假设 / 反例（独立 region，流式追加）
- 底部：🔄 重新生成按钮（force regenerate）
- 多面板堆叠（最多 3 个，z-index 递增）

**`usePrediction.ts` 扩展：** 增加 `openExplanationPanel(nodeId)` 与面板状态管理（`activeExplanationPanels: Ref<Array<{nodeId, scenarioId}>>`）

**`api/explanations.ts`：** `explainStream(scenarioId, nodeId, handlers): SseHandle`、`getRawPrompt(scenarioId): Promise<{rawPrompt: string|null}>`

---

## 5. 错误处理与边界

| 场景 | 行为 |
|---|---|
| 概率值越界 | 前端 clamp [0,1]；后端无值或越界降级为忽略 |
| 概率约束作用于预测节点 | 前端禁止添加 |
| 旧分支无 rawPrompt | API 返回 null，前端提示"该分支创建于早期版本，未保存原始 prompt" |
| 解释 LLM 返回非合法 JSON | 容错关键字切分；仍失败 → SSE error，前端"生成失败，点击重试" |
| nodeId 不在 scenario | 404 |
| 推演运行中触发解释 | 右键菜单项禁用 |
| 切换分支 / 关闭面板 | abort SSE |

---

## 6. 测试

| 类型 | 范围 |
|---|---|
| 后端单测 | `PredictionMath` 先验融合边界、`Constraint` 序列化兼容、`ScenarioExplanationService` 缓存与 404、`LlmService.summarizeConstraints` 三态输出 |
| 后端集成 | `GET /api/scenarios/{id}/raw-prompt` 两种场景 |
| 前端单测 | `PredictDialog` 三态切换 + 概率滑块 + 预测节点禁用 |
| 手工 E2E | 启动 `./start.sh`：建图 → 概率约束推演 → 查看 prompt → 右键看解释 → 切分支后回来命中缓存 |

---

## 7. 工期估算（合计 5d）

| 模块 | 工期 |
|---|---|
| 数据模型扩展 | 0.5d |
| P1-8 prompt 抽取 + API | 0.5d |
| P1-7 explain SSE service | 1d |
| P1-10 后端先验融合 | 0.5d |
| P1-10 PredictDialog 三态 UI | 0.5d |
| P1-8 RawPromptDialog | 0.5d |
| P1-7 ExplanationPanel | 1d |
| 测试 + 联调 | 0.5d |

---

*文档版本：v1.0 · 与 ROADMAP-v2 Phase 1 P1-7/P1-8/P1-10 对齐*

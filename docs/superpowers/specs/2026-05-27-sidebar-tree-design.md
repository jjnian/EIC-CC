# 侧栏工作空间树形结构 设计文档

- 日期：2026-05-27
- 状态：设计已确认，待实现

## 背景

当前侧栏（`frontend/src/components/Sidebar.vue`）把"工作空间"和"对话记录 / 数据源"渲染为三个**平级折叠块**。对话记录和数据源始终只反映"当前工作空间"的内容，无法在侧栏内同时浏览其他工作空间的子节点。

用户需求：希望工作空间是**树形容器**，每个工作空间下面挂着该空间的对话记录和数据源；可以按需展开/收起任一工作空间，互不影响。

## 目标

- 侧栏从三个平级块变为「工作空间嵌套对话/数据源」的二级树
- 任意工作空间可独立展开/收起，展开非当前工作空间也能看到它的对话和数据源
- 子节点懒加载：未展开的工作空间不发起 API 请求
- 展开 ≠ 切换：展开任一工作空间不会改变"当前工作空间"，需另用切换控件
- 默认仅当前工作空间展开；刷新后状态不保留

## 非目标

- 子节点种类不扩展：仅对话记录 + 数据源（不含本体模型、推演分支等）
- 跨工作空间拖拽 / 复制 / 移动条目
- 收起态侧栏（`expanded === false`）的行为与当前一致，不引入新交互
- 持久化展开状态到 localStorage

## 视觉与交互

```
侧栏
┌─────────────────────────────┐
│ 推 推演平台                  │
│ ✦ 新对话                     │
│ ◈ 本体模型                   │
│                              │
│ 工作空间                     │  ← 区块标题
│ ▼ A 工作空间A  [默认] •      │  ← 顶级折叠头(展开)，• = 当前
│   ▶ 💬 对话记录 (3)          │  ← 二级头(收起)
│   ▶ 📂 数据源  (5)           │
│ ▶ B 工作空间B                │  ← 顶级头(收起)，未点过不加载
│ ▼ C 工作空间C                │
│   ▼ 💬 对话记录 (2)          │
│      ● 对话xx                │
│      ● 对话yy                │
│   ▶ 📂 数据源                │
│ ＋ 新建工作空间              │
│                              │
│ ⚙ 设置                       │
└─────────────────────────────┘
```

### 点击规则

| 区域 | 行为 |
|---|---|
| 顶级行（caret + 头像 + 名称区） | 展开 / 收起子树 |
| 顶级行右端的"切换为当前"按钮（保留原圆点 / 加按钮） | 弹确认对话框，切换当前工作空间 |
| 二级头（💬 对话记录 / 📂 数据源） | 展开 / 收起类目；首次展开触发懒加载 |
| 二级头右端的 ＋ / ↻ | 仅在"对话记录"上保留：＋ 新对话（仅当前工作空间）、↻ 重新加载 |
| 二级条目（对话） | 打开会话（仅可在"当前工作空间"内打开 — 见错误处理） |
| 二级条目（数据源） | 仅 hover 显示文件元信息，不可点开 |
| 二级条目右端的 × | 删除该条目 |

### 默认状态

- 首次渲染：仅当前工作空间顶级展开；其下二级头收起
- 切换当前工作空间后：清空所有展开状态，回归"仅新当前工作空间展开"
- 刷新页面：不保留任何展开状态

## 后端改动

`GET /api/conversations` 和 `GET /api/data-sources` 当前都从 `WorkspaceContext.required()` 取请求 header 中的当前工作空间，无法跨空间查询。

**两个端点新增可选 query 参数 `workspaceId`**：
- 不传：保持现状，按 `X-Workspace-Id` header 查
- 传：忽略 header，按 query 指定 workspace 查；workspace 不存在仍走拦截器返回 400

涉及文件：

| 文件 | 改动 |
|---|---|
| `ConversationController.list()` | `@RequestParam(required=false) String workspaceId` 透传给 service |
| `ConversationService.list()` / `list(workspaceId)` | 新重载 |
| `ConversationRepository.list()` / `list(workspaceId)` | 新重载，按显式 wsId 过滤 |
| `DataSourceController.list()` | 同上 |
| `DataSourceRepository.list()` / `list(workspaceId)` | 同上 |

> 兼容性：默认行为不变，现有所有调用点（包括 SSE 流和写操作）保持工作。仅新增懒加载场景需要传 query。

## 前端改动

### `useSidebarTree.ts` 重构

从"单一 ref 存当前工作空间数据"改为"按 wsId 索引的缓存"：

```ts
const cacheConvs   = ref<Record<string, SidebarConversation[]>>({});
const cacheDS      = ref<Record<string, DataSource[]>>({});
const loadingConv  = ref<Record<string, boolean>>({});
const loadingDS    = ref<Record<string, boolean>>({});

loadConversations(wsId: string, force = false)  // 已缓存且未 force → 直接返回
loadDataSources(wsId: string, force = false)
getConversations(wsId: string): SidebarConversation[]
getDataSources(wsId: string): DataSource[]
removeConversation(wsId: string, id: string)
removeDataSource(wsId: string, id: string)
upsertConversation(wsId: string, c: SidebarConversation)
clearCache(wsId?: string)                       // 切换当前工作空间时调用
```

### `conversations.ts` / `dataSources.ts` API

list 函数签名调整，接受可选 `workspaceId`：

```ts
listConversations(opts?: { workspaceId?: string }): Promise<ConversationDto[]>
listDataSources(opts?: { workspaceId?: string }): Promise<DataSource[]>
```

`http.ts` 中的 `request` 不变；调用方在 path 拼好 query string，header 仍按当前工作空间自动附加（后端 query 优先于 header）。

### `Sidebar.vue` 重构

- 移除当前的"对话记录 / 数据源"平级块（行 187-236）
- 工作空间区块改为顶级 `v-for`，每项渲染一个 `WorkspaceNode` 子组件
- 新增 `expandedWs = ref<Set<string>>(new Set([ws.currentId.value]))` 管理顶级展开
- 子组件内部维护 conv / ds 二级展开状态（用两个 `ref<boolean>`）

### 新增 `WorkspaceNode.vue`

抽离单工作空间的渲染，避免 `Sidebar.vue` 进一步膨胀（当前已 607 行）：

- Props：`workspace: Workspace`, `isCurrent: boolean`, `expanded: boolean`
- Emits：`toggle-expand`, `switch-current`, `open-conversation`, `new-conversation`, `delete-conversation`, `delete-data-source`
- 内部：通过 `useSidebarTree` 拿该 wsId 下的 conv / ds，自己管理二级展开
- 顶级展开变化时（watch `expanded`）发起懒加载

## 错误处理

| 场景 | 处理 |
|---|---|
| 加载非当前 workspace 的子节点失败 | toast 提示"加载失败"，二级列表显示"加载失败，点击重试" |
| 删除非当前 workspace 的对话 / 数据源 | API 调用前临时设置 `X-Workspace-Id` 为目标 wsId，删完后恢复（最简方式：在 `request()` 加可选 `workspaceIdOverride` 参数） |
| 在非当前 workspace 下点击对话条目 | 弹确认"将切换到该工作空间打开对话?"，确认后先切换工作空间再打开 |
| 切换当前工作空间过程中树有未完成的懒加载请求 | 不主动 abort（请求量小），切换后 `clearCache()` 让结果作废即可 |

## 测试

- 手测：默认渲染、展开 / 收起、切换当前、新建对话回填、删除条目、跨工作空间打开对话
- 边界：仅 1 个工作空间时；某工作空间无对话/数据源时；网络失败时

## 工时估算

| 阶段 | 估算 |
|---|---|
| 后端两个 list 端点加 workspaceId query | 0.5 天 |
| 前端 API 函数 + useSidebarTree 重构 | 0.5 天 |
| WorkspaceNode 子组件 + Sidebar 改造 | 0.5 天 |
| 联调 + 样式微调 | 0.5 天 |
| **合计** | **2 个工作日** |

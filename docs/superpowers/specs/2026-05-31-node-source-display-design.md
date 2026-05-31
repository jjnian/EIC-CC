# 节点来源展示（数据源 → 库 → 表 → 字段）

日期：2026-05-31
状态：已批准，待实现

## 目标

在图谱节点 / 边的详情弹窗里，展示该节点（或属性、关系）的**完整数据来源**：
来自**哪个数据源 → 哪个数据库 → 哪张表 → 哪个字段**，让用户一眼看清每个本体元素是从哪个真实物理位置抽取来的。

## 现状盘点

来源信息分四级，调查确认如下：

| 层级 | 数据载体 | 抽取时是否有 | 是否已落库 | 是否传到前端 | 是否已展示 |
|------|----------|:----:|:----:|:----:|:----:|
| 数据源名 | `DataSourcePO.name` (`sourceName`) | 有 | **否** | 否 | 否 |
| 数据库名 | `DatabaseSchemaInfo.database()` | 有 | **否** | 否 | 否 |
| 表名 | 节点/边 `derived_tables[]` | 有 | 是 | 是 | **否** |
| 字段名 | 属性 `attributes[].column` | 有 | 是 | 是 | **否** |

关键事实：抽取按**单个数据源**进行（`extractFromDataSource(dsId)`），因此一次抽取产生的整张图，所有节点/边都来自同一个数据源 + 同一个库。

因此本功能要做两件事：
1. **后端**：把"数据源名 / 库名"两级在抽取时落到每个节点和边上（当前完全没存）。
2. **前端**：把已有的"表名 / 字段名"，以及新存的"数据源 / 库名"，在弹窗里展示出来。

## 后端设计：节点级 stamp（方案 A）

给每个节点和边新增两个来源字段，模式与现有 `derived_tables` 完全一致（冗余存到每个节点），在抽取流程末尾统一打标。

选 A 而非"模型级元数据"，理由：与代码库已有的 `derived_tables` 冗余模式一致；且当一个模型未来合并了多次/多源抽取结果时，每个节点的来源仍然准确，不会串源。

### 数据字段命名

- 节点/边新增运行时字段：`derived_source`（数据源名）、`derived_database`（库名）
- 与已有的 `derived_tables`（表名数组）、`attributes[].column`（列名）一起，构成完整四级来源。

### 改动点

**1. 实体 PO（4 个）新增字段 + getter/setter**
- `OntologyNodePO` / `OntologyVersionNodePO`：加 `derivedSource`、`derivedDatabase`
- `OntologyEdgePO` / `OntologyVersionEdgePO`：加 `derivedSource`、`derivedDatabase`

**2. SQL（`init.sql`）**
- `ontology_node` / `ontology_edge` / `ontology_version_node` / `ontology_version_edge` 四张表：
  - `CREATE TABLE` 中加列 `derived_source VARCHAR(255)`、`derived_database VARCHAR(255)`
  - 对应的 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...` 各加两列（兼容老库）

**3. 持久化读写（2 个 Repository）**
- `OntologyModelRepository`：`nodeToMap` / `edgeToMap` 回读时还原 `derived_source` / `derived_database`；`insertNode` / `insertEdge` 写入时落库。
- `OntologyVersionRepository`：`insertVersionNode/Edge`、`loadVersionNodes/Edges` 同样处理。

**4. 抽取时打标（`SchemaOntologyService`）**
- 在 `extractFromDataSource` 末尾（节点/边已生成、`out` 已构造），遍历所有 `add_nodes` / `add_edges`，给每个节点/边写入 `derived_source = sourceName`、`derived_database = schema.database()`。
- 在 fact-check / fallback / coverage 路径中新建的节点/边也会经过这步统一打标，无需在每个 `buildFallbackNode` 等处分别写。

### 后端不改的部分

- `DataSourceController.extractOntology` 的 SSE payload 透传 `r.payload()` 的 nodes/edges，节点上多了两个字段会自动带出，无需改 Controller。
- LLM prompt 不动——数据源/库名是后端已知的环境信息，不需要 LLM 产出。

### 兼容旧数据

旧模型的节点没有 `derived_source` / `derived_database`，回读为 null。前端展示时这两级缺失则显示 `—` 或隐藏对应行，不报错。

## 前端设计：弹窗展示

纯展示，不改任何编辑 / 保存逻辑。

### 1. 类型定义（`types.ts`）

- `OntologyNode` / `OntologyEdge`：补 `derived_tables?: string[]`、`derived_source?: string`、`derived_database?: string`（运行时本就有，补类型让模板有提示）。
- `OntologyAttribute`：补 `column?: string`。

### 2. NodeInfo 概览 Tab：新增"数据来源"卡片

放在"节点详情"卡片下方。仅当节点有任一来源字段时显示。

```
┌─ 数据来源 ───────────────────────┐
│ 数据源   销售库MySQL              │
│ 数据库   sales_db                │
│ 来源表   [orders] [order_items]  │   ← chips，多表并排
└──────────────────────────────────┘
```

- 数据源 / 数据库：取 `node.derived_source` / `node.derived_database`，缺失显示 `—`。
- 来源表：遍历 `node.derived_tables`，渲染成 chip 列表；空则显示"暂无来源表"。

### 3. NodeInfo 属性 Tab：每个属性补物理列名（行内紧凑形式）

在现有"取值空间"行，把物理列名以灰色小字跟随展示：

```
本体属性 (5)
┌──────────────────────────────┐
│ 订单金额            [文本提取] ✕ │
│ number  ·  orders.amount        │   ← 灰色列名跟随
└──────────────────────────────┘
```

- 列名取 `a.column`。若属性有来源表上下文，显示为 `表.列`（如 `orders.amount`）；只有列名时显示 `· 列名`。
- 缺 `column` 时只显示取值空间，不显示分隔点。
- 保持取值空间可编辑输入框不变，列名是只读展示。

### 4. EdgeInfo 概览 Tab：加来源信息

"关系详情"卡片里：
- 加"来源表"行：渲染 `edge.derived_tables` chips。
- 加"数据源"行、"数据库"行（与节点概览的数据来源卡片字段一致，各占一行）。
- 边的 `label` 本就是列级血缘（形如 `order_items.order_id → orders.id`），在"边方向"卡片已有展示，保持不变；可加一行小字说明这是"字段级血缘"。

## 测试

- **后端**：抽取一个 MySQL/PG 数据源 → 确认生成的节点/边 JSON 带 `derived_source` / `derived_database`；保存模型后重新 `GET /api/ontology-models/{id}` → 确认两字段回读正确；做一次版本快照 + 恢复 → 确认版本表也保留。
- **前端**：
  - 新抽取的模型：点节点 → 概览看到数据源/库/表，属性 Tab 看到列名；点边 → 看到来源表。
  - 旧模型（无 derived_source）：不报错，缺失级显示 `—` 或隐藏。
  - 手动新建的节点（无来源）：来源卡片隐藏或显示空状态。

## 不做（YAGNI）

- 不让 LLM 产出数据源/库名。
- 不做来源信息的编辑能力（来源是抽取事实，只读）。
- 不改 SchemaPanel 侧边栏（本次聚焦弹窗；如需要可后续单独做）。

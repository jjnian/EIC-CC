# 节点来源展示（数据源 → 库 → 表 → 字段）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在节点/边的详情弹窗里展示完整的四级数据来源（数据源 → 数据库 → 表 → 字段），让用户知道每个本体元素从哪个真实物理位置抽取而来。

**Architecture:** 后端把抽取时已知的"数据源名/库名"以节点级 stamp 方式落到每个节点和边上（与现有 `derived_tables` 冗余模式一致），表名/列名后端本已存储并回传；前端在三个弹窗位置把这四级来源展示出来，纯展示不改编辑逻辑。

**Tech Stack:** 后端 Spring Boot + MyBatis-Plus + Jackson + PostgreSQL；前端 Vue 3 `<script setup>` + TypeScript + Vite。

**测试说明：** 本项目后端无 `src/test`、前端无测试框架（package.json 仅有 `build` 与 `lint=tsc --noEmit`）。为一个纯展示功能新建测试框架属于 YAGNI 过度设计。因此每个任务的验证门为：后端 `mvn compile`、前端 `npm run lint`（类型检查）+ `npm run build`，最后做一次端到端手动验证。

参考设计文档：`docs/superpowers/specs/2026-05-31-node-source-display-design.md`

---

## File Structure

**后端（落库 + 打标，新增 `derived_source` / `derived_database` 两字段）**
- Modify: `backend/src/main/java/com/tuiyan/backend/entity/OntologyNodePO.java` — 加 2 字段 + getter/setter
- Modify: `backend/src/main/java/com/tuiyan/backend/entity/OntologyEdgePO.java` — 加 2 字段 + getter/setter
- Modify: `backend/src/main/java/com/tuiyan/backend/entity/OntologyVersionNodePO.java` — 加 2 字段 + getter/setter
- Modify: `backend/src/main/java/com/tuiyan/backend/entity/OntologyVersionEdgePO.java` — 加 2 字段 + getter/setter
- Modify: `backend/src/main/resources/sql/init.sql` — 4 张表 CREATE + ALTER 各加 2 列
- Modify: `backend/src/main/java/com/tuiyan/backend/repository/OntologyModelRepository.java` — nodeToMap/edgeToMap 读 + insertNode/insertEdge 写
- Modify: `backend/src/main/java/com/tuiyan/backend/repository/OntologyVersionRepository.java` — insertVersionNode/Edge 写 + loadVersionNodes/Edges 读
- Modify: `backend/src/main/java/com/tuiyan/backend/service/SchemaOntologyService.java` — 抽取末尾给每个节点/边打标

**前端（纯展示）**
- Modify: `frontend/src/types.ts` — 补类型字段
- Modify: `frontend/src/components/NodeInfo.vue` — 概览加"数据来源"卡片 + 属性 Tab 加列名
- Modify: `frontend/src/components/EdgeInfo.vue` — 概览加来源行

字段命名（全程一致）：运行时 JSON key 为 `derived_source`、`derived_database`、`derived_tables`；PO 字段为 `derivedSource`、`derivedDatabase`、`derivedTablesJson`。

---

## Task 1: 后端实体 PO 新增来源字段

**Files:**
- Modify: `backend/src/main/java/com/tuiyan/backend/entity/OntologyNodePO.java`
- Modify: `backend/src/main/java/com/tuiyan/backend/entity/OntologyEdgePO.java`
- Modify: `backend/src/main/java/com/tuiyan/backend/entity/OntologyVersionNodePO.java`
- Modify: `backend/src/main/java/com/tuiyan/backend/entity/OntologyVersionEdgePO.java`

- [ ] **Step 1: `OntologyNodePO` 加字段**

在 `private String derivedTablesJson;` 之后插入：

```java
    private String derivedSource;
    private String derivedDatabase;
```

在 `setDerivedTablesJson` 的 getter/setter 之后插入：

```java
    public String getDerivedSource() { return derivedSource; }
    public void setDerivedSource(String derivedSource) { this.derivedSource = derivedSource; }
    public String getDerivedDatabase() { return derivedDatabase; }
    public void setDerivedDatabase(String derivedDatabase) { this.derivedDatabase = derivedDatabase; }
```

- [ ] **Step 2: `OntologyVersionNodePO` 加同样字段与方法**

字段放在 `derivedTablesJson` 之后，方法放在其 getter/setter 之后（代码同 Step 1）。

- [ ] **Step 3: `OntologyEdgePO` 加字段**

在 `private String derivedTablesJson;` 之后插入字段，在 `setDerivedTablesJson` 之后插入 getter/setter（代码同 Step 1 的两字段块）。

- [ ] **Step 4: `OntologyVersionEdgePO` 加同样字段与方法**

字段放在 `derivedTablesJson` 之后，方法放在其 getter/setter 之后（代码同 Step 1）。

- [ ] **Step 5: 编译验证**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS（PO 仅加字段，此时尚无引用，必然通过）

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/tuiyan/backend/entity/OntologyNodePO.java backend/src/main/java/com/tuiyan/backend/entity/OntologyEdgePO.java backend/src/main/java/com/tuiyan/backend/entity/OntologyVersionNodePO.java backend/src/main/java/com/tuiyan/backend/entity/OntologyVersionEdgePO.java
git commit -m "feat(entity): 节点/边 PO 新增 derived_source/derived_database 来源字段"
```

---

## Task 2: SQL 表结构加列

**Files:**
- Modify: `backend/src/main/resources/sql/init.sql`

- [ ] **Step 1: `ontology_node` 表**

在 CREATE TABLE 的 `attributes_json TEXT,` 行后加列：

```sql
    derived_source          VARCHAR(255),
    derived_database        VARCHAR(255),
```

并把该表已有的 `ALTER TABLE ontology_node ADD COLUMN IF NOT EXISTS ...` 块补两列：

```sql
ALTER TABLE ontology_node
    ADD COLUMN IF NOT EXISTS derived_tables_json TEXT,
    ADD COLUMN IF NOT EXISTS attributes_json TEXT,
    ADD COLUMN IF NOT EXISTS constraints_json TEXT,
    ADD COLUMN IF NOT EXISTS derived_source VARCHAR(255),
    ADD COLUMN IF NOT EXISTS derived_database VARCHAR(255);
```

- [ ] **Step 2: `ontology_edge` 表**

CREATE 中 `derived_tables_json TEXT,` 后加 `derived_source VARCHAR(255),` 与 `derived_database VARCHAR(255),`；对应 ALTER 块补：

```sql
ALTER TABLE ontology_edge
    ADD COLUMN IF NOT EXISTS derived_tables_json TEXT,
    ADD COLUMN IF NOT EXISTS constraints_json TEXT,
    ADD COLUMN IF NOT EXISTS derived_source VARCHAR(255),
    ADD COLUMN IF NOT EXISTS derived_database VARCHAR(255);
```

- [ ] **Step 3: `ontology_version_node` 表**

CREATE 中 `attributes_json TEXT,` 后加两列；ALTER 块（`ALTER TABLE ontology_version_node ...`）补：

```sql
    ADD COLUMN IF NOT EXISTS derived_source VARCHAR(255),
    ADD COLUMN IF NOT EXISTS derived_database VARCHAR(255);
```

（接在该块已有的 `ADD COLUMN IF NOT EXISTS constraints_json TEXT` 之后，注意把原本结尾的 `;` 改到最后一行。）

- [ ] **Step 4: `ontology_version_edge` 表**

CREATE 中 `derived_tables_json TEXT,` 后加两列；ALTER 块（`ALTER TABLE ontology_version_edge ...`）同样补两列 `derived_source` / `derived_database`，注意分号收尾。

- [ ] **Step 5: 提示用户重启后端以执行 schema 迁移**

`init.sql` 在应用启动时执行（`ALTER ... IF NOT EXISTS` 幂等）。本步无需运行命令，记录：后端重启后新列才会生效。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/sql/init.sql
git commit -m "feat(sql): 本体节点/边 4 表新增 derived_source/derived_database 列"
```

---

## Task 3: 持久化层读写来源字段

**Files:**
- Modify: `backend/src/main/java/com/tuiyan/backend/repository/OntologyModelRepository.java`
- Modify: `backend/src/main/java/com/tuiyan/backend/repository/OntologyVersionRepository.java`

- [ ] **Step 1: `OntologyModelRepository.nodeToMap` 回读**

在 `nodeToMap` 方法中，紧接处理 `derived_tables` 的块之后（`if (n.getDerivedTablesJson() ...) { m.put("derived_tables", ...); }`）加入：

```java
        if (n.getDerivedSource() != null) m.put("derived_source", n.getDerivedSource());
        if (n.getDerivedDatabase() != null) m.put("derived_database", n.getDerivedDatabase());
```

- [ ] **Step 2: `OntologyModelRepository.edgeToMap` 回读**

在 `edgeToMap` 处理 `derived_tables` 块之后加入：

```java
        if (e.getDerivedSource() != null) m.put("derived_source", e.getDerivedSource());
        if (e.getDerivedDatabase() != null) m.put("derived_database", e.getDerivedDatabase());
```

- [ ] **Step 3: `OntologyModelRepository.insertNode` 写入**

在 `po.setDerivedTablesJson(codec.toJson(n.get("derived_tables")));` 之后加入：

```java
        po.setDerivedSource(asString(n.get("derived_source")));
        po.setDerivedDatabase(asString(n.get("derived_database")));
```

- [ ] **Step 4: `OntologyModelRepository.insertEdge` 写入**

在 `po.setDerivedTablesJson(codec.toJson(e.get("derived_tables")));` 之后加入：

```java
        po.setDerivedSource(asString(e.get("derived_source")));
        po.setDerivedDatabase(asString(e.get("derived_database")));
```

- [ ] **Step 5: `OntologyVersionRepository.insertVersionNode` 写入**

在 `po.setDerivedTablesJson(codec.toJson(n.get("derived_tables")));` 之后加入：

```java
        po.setDerivedSource(asString(n.get("derived_source")));
        po.setDerivedDatabase(asString(n.get("derived_database")));
```

- [ ] **Step 6: `OntologyVersionRepository.insertVersionEdge` 写入**

在 `po.setDerivedTablesJson(codec.toJson(e.get("derived_tables")));` 之后加入：

```java
        po.setDerivedSource(asString(e.get("derived_source")));
        po.setDerivedDatabase(asString(e.get("derived_database")));
```

- [ ] **Step 7: `OntologyVersionRepository.loadVersionNodes` 回读**

在该方法处理节点 `derived_tables` 的块之后（`m.put("derived_tables", ...)`）加入：

```java
            if (n.getDerivedSource() != null) m.put("derived_source", n.getDerivedSource());
            if (n.getDerivedDatabase() != null) m.put("derived_database", n.getDerivedDatabase());
```

（注意此处在循环内，缩进与周围 `m.put` 一致。）

- [ ] **Step 8: `OntologyVersionRepository.loadVersionEdges` 回读**

在该方法处理边 `derived_tables` 的块之后加入：

```java
            if (e.getDerivedSource() != null) m.put("derived_source", e.getDerivedSource());
            if (e.getDerivedDatabase() != null) m.put("derived_database", e.getDerivedDatabase());
```

- [ ] **Step 9: 编译验证**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/tuiyan/backend/repository/OntologyModelRepository.java backend/src/main/java/com/tuiyan/backend/repository/OntologyVersionRepository.java
git commit -m "feat(repo): 持久化与版本快照读写 derived_source/derived_database"
```

---

## Task 4: 抽取流程给节点/边打标

**Files:**
- Modify: `backend/src/main/java/com/tuiyan/backend/service/SchemaOntologyService.java`

`extractFromDataSource` 中，`out` 经 `applyImportSalt` 得到，含 `nodes` / `edges` 两个数组（见该文件 ~150-151 行）。`sourceName`（数据源名，方法内已有局部变量）与 `schema.database()`（库名）都已就绪。`applyImportSalt` 对每个节点 deepCopy 并保留所有字段，因此在其后直接给每个 node/edge 补两字段即可。

- [ ] **Step 1: 加打标逻辑**

在 `extractFromDataSource` 方法内，`out.put("reply", ...)` 那行**之前**（即 `ObjectNode out = (ObjectNode) rewritten;` 之后）插入：

```java
        // 给每个节点/边打上数据源 + 库名来源（整张图同源，统一 stamp）
        String derivedDatabase = schema.database();
        stampSource(out.path("nodes"), sourceName, derivedDatabase);
        stampSource(out.path("edges"), sourceName, derivedDatabase);
```

- [ ] **Step 2: 新增 `stampSource` 私有方法**

在 `extractFromDataSource` 方法结束的 `}` 之后（与其它私有方法同级）加入：

```java
    /** 给 nodes/edges 数组里每个对象补 derived_source / derived_database。整张图同源时统一打标。 */
    private void stampSource(JsonNode arr, String source, String database) {
        if (!(arr instanceof ArrayNode list)) return;
        for (JsonNode n : list) {
            if (!(n instanceof ObjectNode obj)) continue;
            if (source != null && !source.isBlank()) obj.put("derived_source", source);
            if (database != null && !database.isBlank()) obj.put("derived_database", database);
        }
    }
```

（`ArrayNode` / `ObjectNode` / `JsonNode` 在本文件已 import，无需新增。）

- [ ] **Step 3: 编译验证**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/tuiyan/backend/service/SchemaOntologyService.java
git commit -m "feat(extract): 抽取末尾给每个节点/边打数据源+库名来源标记"
```

---

## Task 5: 前端类型定义

**Files:**
- Modify: `frontend/src/types.ts`

- [ ] **Step 1: `OntologyNode` 补字段**

在 `OntologyNode` 接口内，`constraints?: OntologyConstraint[];` 行之后、`predictedStep?` 之前加入：

```ts
  // 数据来源（DB schema 抽取时打标）
  derived_tables?: string[];
  derived_source?: string;
  derived_database?: string;
```

- [ ] **Step 2: `OntologyEdge` 补字段**

在 `OntologyEdge` 接口内，`constraints?: OntologyConstraint[];` 行之后加入：

```ts
  derived_tables?: string[];
  derived_source?: string;
  derived_database?: string;
```

- [ ] **Step 3: `OntologyAttribute` 补 column**

在 `OntologyAttribute` 接口内 `valueSpace?: string;` 之后加入：

```ts
  column?: string;
```

- [ ] **Step 4: 类型检查**

Run: `cd frontend && npm run lint`
Expected: 无报错（仅加可选字段）

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types.ts
git commit -m "feat(types): OntologyNode/Edge/Attribute 补来源字段类型"
```

---

## Task 6: NodeInfo 概览加"数据来源"卡片

**Files:**
- Modify: `frontend/src/components/NodeInfo.vue`

- [ ] **Step 1: 在概览 Tab 的"节点详情"卡片之后插入来源卡片**

在 `<template v-if="tab === 0">` 内，"节点详情" `</div>`（该 `ni-card` 闭合）之后、`</template>` 之前插入：

```vue
                <div v-if="node.derived_source || node.derived_database || (node.derived_tables || []).length" class="ni-card">
                  <div class="ni-card-title">数据来源</div>
                  <div class="ni-grid">
                    <div class="ni-cell"><div class="ni-cell-k">数据源</div><div class="ni-cell-v">{{ node.derived_source || '—' }}</div></div>
                    <div class="ni-cell"><div class="ni-cell-k">数据库</div><div class="ni-cell-v mono">{{ node.derived_database || '—' }}</div></div>
                    <div class="ni-cell ni-cell-wide"><div class="ni-cell-k">来源表</div>
                      <div class="ni-cell-v">
                        <span v-for="(tb, i) in (node.derived_tables || [])" :key="'dt'+i" class="ni-src-chip">{{ tb }}</span>
                        <span v-if="!(node.derived_tables || []).length" class="ni-dim">暂无来源表</span>
                      </div>
                    </div>
                  </div>
                </div>
```

- [ ] **Step 2: 加样式**

在 `<style scoped>` 块末尾（若该文件无 scoped style 块，则在 `</template>` 之后新建 `<style scoped></style>`）加入：

```css
.ni-src-chip {
  display: inline-block;
  padding: 2px 8px;
  margin: 2px 4px 2px 0;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  color: #22dd88;
  background: rgba(34, 221, 136, 0.12);
  border-radius: 6px;
}
.ni-dim { color: rgba(255,255,255,0.4); font-size: 12px; }
.ni-cell-wide { grid-column: 1 / -1; }
```

- [ ] **Step 3: 类型检查 + 构建**

Run: `cd frontend && npm run lint && npm run build`
Expected: 无报错，构建成功

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/NodeInfo.vue
git commit -m "feat(NodeInfo): 概览新增数据来源卡片(数据源/库/来源表)"
```

---

## Task 7: NodeInfo 属性 Tab 展示物理列名

**Files:**
- Modify: `frontend/src/components/NodeInfo.vue`

属性 Tab 中每个属性现有一个"取值空间"只读/输入行：
`<input class="ni-inline-input mono" :value="a.valueSpace" placeholder="取值空间" @change="..." />`
设计为行内紧凑：在取值空间后跟随灰色 `表.列` 或 `· 列名`。

- [ ] **Step 1: 加列名展示计算辅助**

在 `<script setup>` 中（`sourceBadge` 函数附近）加入：

```ts
// 属性物理列来源：有来源表时显示 "表.列"，否则 "列"
const attrColumnText = (a: any, node: any): string => {
  if (!a?.column) return '';
  const tables = node?.derived_tables || [];
  const table = tables.length === 1 ? tables[0] : '';
  return table ? `${table}.${a.column}` : a.column;
};
```

- [ ] **Step 2: 改属性行模板，把取值空间与列名放同一行**

把属性项里的取值空间 input 那一行：

```vue
                      <input class="ni-inline-input mono" :value="a.valueSpace" placeholder="取值空间" @change="(e: any) => updateAttribute(i, 'valueSpace', e.target.value)" />
```

替换为：

```vue
                      <div class="ni-attr-meta">
                        <input class="ni-inline-input mono ni-attr-vs" :value="a.valueSpace" placeholder="取值空间" @change="(e: any) => updateAttribute(i, 'valueSpace', e.target.value)" />
                        <span v-if="a.column" class="ni-attr-col">· {{ attrColumnText(a, node) }}</span>
                      </div>
```

- [ ] **Step 3: 加样式**

在 Task 6 Step2 新建的 `<style scoped>` 块末尾加入（NodeInfo.vue 原本无 style 块，由 Task 6 创建）：

```css
.ni-attr-meta { display: flex; align-items: center; gap: 8px; }
.ni-attr-vs { flex: 0 1 auto; }
.ni-attr-col {
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  color: rgba(255,255,255,0.45);
  white-space: nowrap;
}
```

- [ ] **Step 4: 类型检查 + 构建**

Run: `cd frontend && npm run lint && npm run build`
Expected: 无报错，构建成功

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/NodeInfo.vue
git commit -m "feat(NodeInfo): 属性 Tab 行内展示物理列名(表.列)"
```

---

## Task 8: EdgeInfo 概览加来源信息

**Files:**
- Modify: `frontend/src/components/EdgeInfo.vue`

- [ ] **Step 1: 在"关系详情"卡片的 ni-grid 内加来源行**

在 EdgeInfo 概览 Tab "关系详情" `ni-grid` 内，"约束数" 那个 `ni-cell` 之后加入：

```vue
                  <div class="ni-cell ni-cell-wide"><div class="ni-cell-k">来源表</div>
                    <div class="ni-cell-v">
                      <span v-for="(tb, i) in (edge.derived_tables || [])" :key="'edt'+i" class="ei-src-chip">{{ tb }}</span>
                      <span v-if="!(edge.derived_tables || []).length" class="ni-dim">暂无来源表</span>
                    </div>
                  </div>
                  <div class="ni-cell"><div class="ni-cell-k">数据源</div><div class="ni-cell-v">{{ edge.derived_source || '—' }}</div></div>
                  <div class="ni-cell"><div class="ni-cell-k">数据库</div><div class="ni-cell-v mono">{{ edge.derived_database || '—' }}</div></div>
```

- [ ] **Step 2: 加样式**

在 EdgeInfo 的 `<style scoped>` 末尾加入：

```css
.ei-src-chip {
  display: inline-block;
  padding: 2px 8px;
  margin: 2px 4px 2px 0;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  color: #22dd88;
  background: rgba(34, 221, 136, 0.12);
  border-radius: 6px;
}
.ni-dim { color: rgba(255,255,255,0.4); font-size: 12px; }
.ni-cell-wide { grid-column: 1 / -1; }
```

- [ ] **Step 3: 类型检查 + 构建**

Run: `cd frontend && npm run lint && npm run build`
Expected: 无报错，构建成功

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/EdgeInfo.vue
git commit -m "feat(EdgeInfo): 概览展示边的来源表/数据源/数据库"
```

---

## Task 9: 端到端手动验证

**Files:** 无（验证任务）

- [ ] **Step 1: 启动后端（执行 schema 迁移）**

Run: `cd backend && mvn spring-boot:run`
Expected: 启动成功，`init.sql` 的 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 给 4 张表加上新列。

- [ ] **Step 2: 启动前端**

Run: `cd frontend && npm run dev`
Expected: Vite dev server 起在本地端口。

- [ ] **Step 3: 跑一次 DB schema → 本体抽取**

在前端从一个 MySQL/PG 数据源执行"一键生成本体血缘图"，保存为模型。

- [ ] **Step 4: 验证节点弹窗**

点击一个节点 → 概览 Tab：看到"数据来源"卡片含数据源名、库名、来源表 chips；切到属性 Tab：每个属性后看到灰色 `表.列`（如 `orders.amount`）。

- [ ] **Step 5: 验证边弹窗**

点击一条边 → 概览 Tab："关系详情"里看到来源表 chips + 数据源 + 数据库；"边方向"里 label 仍是列级血缘（如 `order_items.order_id → orders.id`）。

- [ ] **Step 6: 验证旧模型不报错**

打开一个本次改动前就存在的旧模型，点节点/边 → 来源卡片中数据源/库显示 `—`，无来源表时显示"暂无来源表"，不报错。

- [ ] **Step 7: 验证版本快照**

对新模型做一次保存/快照后再恢复 → 节点/边的来源信息仍在。

---

## Self-Review

**1. Spec coverage（逐条核对设计文档）：**
- 后端节点级 stamp（derived_source/derived_database）→ Task 1（PO）+ Task 2（SQL）+ Task 3（读写）+ Task 4（打标）✅
- 兼容旧数据（null 时显示 —/隐藏）→ Task 6 Step1 `v-if`、Task 9 Step6 ✅
- 前端类型定义 → Task 5 ✅
- NodeInfo 概览数据来源卡片 → Task 6 ✅
- NodeInfo 属性 Tab 行内列名（紧凑形式）→ Task 7 ✅
- EdgeInfo 概览来源表 + 数据源 + 数据库（各占一行）→ Task 8 ✅
- 边 label 列级血缘保持不变 → Task 8 未改 label，Task 9 Step5 验证 ✅
- 不改 SchemaPanel / 不让 LLM 产出 / 来源只读 → 计划无此类任务 ✅

**2. Placeholder scan：** 无 TBD/TODO；每个改代码步骤都给了完整代码块。✅

**3. Type consistency：** JSON key 全程 `derived_source`/`derived_database`/`derived_tables`/`column`；PO 方法 `getDerivedSource/setDerivedSource`、`getDerivedDatabase/setDerivedDatabase` 在 Task 1 定义、Task 3 调用一致；`stampSource` 在 Task 4 定义并调用，签名一致；`attrColumnText` 在 Task 7 定义并调用一致。✅

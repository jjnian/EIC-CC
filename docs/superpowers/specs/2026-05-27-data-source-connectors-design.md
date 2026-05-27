# 多类型数据源接入 设计文档

- 日期：2026-05-27
- 状态：设计已确认，待实现
- 关联：[侧栏工作空间树形结构](./2026-05-27-sidebar-tree-design.md)

## 背景

当前系统的"数据源"概念局限于 `/api/ontology-models/extract` 流程产生的副产物：每次成功抽取后，将上传过的文件 / 网址写入 `data_source` 表，仅作为"导入历史"展示在侧栏。该表只支持 `kind = file | url` 两种取值，且无连接配置、无后续可查询能力。

业务希望把"数据源"升级为持久挂载的**活资源**：

- 接入 MySQL / PostgreSQL 数据库，可看表、查行、写 SQL
- 上传 PDF / TXT / Markdown 文件作为独立数据源（与现有 extract 流程的"导入历史"区分），保留原文与文本预览
- 接入 HTTPS REST 接口，可手动执行、可定时拉取，保留响应历史

## 目标

- 用户在侧栏「📂 数据源」节点下能看到 4 类新数据源条目，点击进入详情视图
- 4 类数据源各自的"创建 → 测试 → 预览 / 执行"闭环可用
- 凭证以明文存储（与现有 LLM API key 一致）
- 与现有 `data_source` 表共存，旧记录不受影响

## 非目标

- LLM tool calling / function call 调用数据源（**留待下一期**）
- 数据库 schema 自动转节点 / 边
- 凭证加密
- HTTPS Webhook 反向回调
- 文件多版本管理
- 跨工作空间共享数据源
- 扫描件 PDF 的 OCR 兜底（与 extract 流程的 PDF 渲染兜底不同；本期上传仅做文本抽取，扫描件得到的就是空白或稀疏文本）
- SSRF 白名单 / 关闭开关（本期硬编码拒绝内网，无前端配置）

## 术语

| 术语 | 含义 |
|---|---|
| **kind** | 数据源类型枚举字段，旧值 `file` `url`，新值 `mysql` `pgsql` `file_stored` `https_api` |
| **导入历史** | 旧记录（`kind=file/url`），由 extract 流程被动创建，不可再操作 |
| **活数据源** | 新记录（其他四种 kind），由"➕ 添加数据源"主动创建，可测试、预览、执行 |
| **fetch_log** | HTTPS 数据源的执行历史，每条记录一次手动或定时拉取的结果 |

## 数据模型

### `data_source` 表字段扩展

向后兼容地增列：

```sql
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS config_json    TEXT;
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS status         VARCHAR(16) DEFAULT 'idle';
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS last_tested_at BIGINT;
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS last_error     TEXT;
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS updated_at     BIGINT;
```

| 列 | 含义 |
|---|---|
| `kind` | `file` / `url`（旧）；新增 `mysql` / `pgsql` / `file_stored` / `https_api` |
| `config_json` | 类型特定的连接配置（host/port/url/method/...），按 kind 不同结构不同；旧 kind 为 NULL |
| `extra_json` | 现有列，保留：旧 kind 存 pages/chars/title 等抽取元信息；新 kind 不写入此列 |
| `status` | `idle` / `connected` / `error` |
| `last_tested_at` | 上次连接测试时间戳（毫秒） |
| `last_error` | 上次失败原因 |
| `updated_at` | 配置最近修改时间 |

旧记录 `status` 默认 `idle`，`config_json` 为 NULL，不影响现有侧栏渲染。

### `config_json` 各 kind 结构

**mysql / pgsql：**
```json
{
  "host": "localhost",
  "port": 3306,
  "database": "demo",
  "username": "root",
  "password": "xxx",
  "params": "useSSL=false&serverTimezone=UTC"
}
```

**file_stored：**
```json
{
  "storagePath": "datasource-files/ds_xxx/原文件名.pdf",
  "extractedTextPath": "datasource-files/ds_xxx/原文件名.txt",
  "chars": 12345,
  "pages": 8
}
```

- 原文件存盘 `~/.tuiyan/datasource-files/<id>/`
- PDF 上传时同步抽出纯文本另存 `.txt` 旁挂；预览读 txt（不再每次解析 PDF）
- TXT/MD 直接当文本，无需二次处理

**https_api：**
```json
{
  "url": "https://api.example.com/users",
  "method": "GET",
  "headers": { "Authorization": "Bearer xxx", "Accept": "application/json" },
  "body": "",
  "bodyType": "json",
  "timeoutMs": 15000,
  "schedule": { "enabled": false, "intervalSec": 300 }
}
```

### 新增 `data_source_fetch_log` 表

```sql
CREATE TABLE IF NOT EXISTS data_source_fetch_log (
    id              BIGSERIAL    PRIMARY KEY,
    data_source_id  VARCHAR(64)  NOT NULL REFERENCES data_source(id) ON DELETE CASCADE,
    fetched_at      BIGINT       NOT NULL,
    status_code     INTEGER,
    success         BOOLEAN      NOT NULL,
    response_body   TEXT,
    error_msg       TEXT,
    duration_ms     INTEGER
);
CREATE INDEX IF NOT EXISTS idx_fetch_log_ds_at
    ON data_source_fetch_log (data_source_id, fetched_at DESC);
```

- 单数据源最多保留 **20 条**最近日志，超出滚动删除（每次写入后清理）
- `response_body` 截断到 100 KB 后入表；超过 1 MB 的响应整体不读，仅 `error_msg` 标注「响应过大已丢弃」

## 后端组件

### 包结构（新增 / 改动）

```
backend/src/main/java/com/tuiyan/backend/
├── controller/
│   └── DataSourceController.java          [改] 新增 POST/PUT/test/preview/execute
├── service/
│   ├── DataSourceService.java             [新] CRUD + 调度入口
│   └── connector/
│       ├── JdbcConnectorService.java      [新] MySQL/PgSQL 通用 JDBC
│       ├── HttpConnectorService.java      [新] HTTPS 执行
│       ├── FileStoredService.java         [新] 文件落盘 + 抽文本
│       └── HttpScheduler.java             [新] 定时拉取调度
├── entity/
│   ├── DataSourcePO.java                  [改] 加 configJson/status/...
│   └── DataSourceFetchLogPO.java          [新]
├── mapper/
│   ├── DataSourceMapper.java              [改]
│   └── DataSourceFetchLogMapper.java      [新]
├── repository/
│   ├── DataSourceRepository.java          [改]
│   └── DataSourceFetchLogRepository.java  [新]
└── model/dto/
    ├── DataSourceCreateRequest.java       [新]
    ├── DataSourceTestResponse.java        [新]
    ├── TablePreviewResponse.java          [新]
    ├── SqlExecuteRequest.java             [新]
    └── HttpExecuteResponse.java           [新]
```

### REST API

#### 通用

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/data-sources` | 列表（保留），新增 `kind` query 过滤 |
| `POST` | `/api/data-sources` | 新增：创建（按 kind 校验 config） |
| `PUT` | `/api/data-sources/{id}` | 新增：编辑 |
| `DELETE` | `/api/data-sources/{id}` | 保留：删 file_stored 时级联清理磁盘 |
| `POST` | `/api/data-sources/{id}/test` | 新增：连通性测试，写回 status / last_error |

#### 数据库专用（mysql / pgsql）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/data-sources/{id}/tables` | 列出 schema 下所有表名 |
| `GET` | `/api/data-sources/{id}/tables/{name}/preview?limit=50` | 预览前 N 行 |
| `POST` | `/api/data-sources/{id}/sql` | 执行 SQL（body: `{sql, limit}`） |

#### 文件专用（file_stored）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/data-sources/file` | multipart 上传 PDF/TXT/MD 创建数据源 |
| `GET` | `/api/data-sources/{id}/content?range=0-10000` | 取已抽取文本（分段） |
| `GET` | `/api/data-sources/{id}/download` | 下载原文件 |

#### HTTPS 专用

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/data-sources/{id}/execute` | 立即执行一次，写入 fetch_log，返回响应 |
| `GET` | `/api/data-sources/{id}/logs` | 拉最近 20 条 fetch_log |
| `PUT` | `/api/data-sources/{id}/schedule` | 启停定时拉取 |

### 安全护栏

| 风险 | 措施 |
|---|---|
| SQL 注入 / 误删 | `JdbcConnectorService.executeSql()` 仅允许 SELECT/SHOW/DESC/EXPLAIN（前缀白名单 + 正则剔除 `;` 后续语句）；强制带 `LIMIT`（默认 100、最大 1000） |
| 数据库连接泄漏 | 每次操作用临时 `HikariDataSource`（minIdle=0, maxPoolSize=2），用完关闭；不缓存 |
| HTTPS SSRF | 复用现有 `WebPageFetcher` 的 SSRF 校验逻辑（拒绝 loopback/link-local/内网）；本期硬编码拒绝，不暴露关闭开关 |
| HTTPS 响应过大 | 读取阶段超过 1 MB 直接截断丢弃；100 KB ~ 1 MB 之间截断至 100 KB 后入表 |
| 文件类型仿冒 | 复用 `FileSniffer` 魔术字节校验，PDF/TXT/MD 之外的拒绝 |
| 文件大小 | PDF ≤ 12 MB，TXT/MD ≤ 4 MB |
| 定时拉取打爆服务 | `intervalSec` 最小 60；同时活跃任务 ≤ 20；超过 toast 拒绝启用 |

### 定时拉取实现

- 用 Spring `TaskScheduler` 单线程池（不复用 `predictionExecutor`）
- 启动时从 DB 加载所有 `schedule.enabled=true` 的 https_api 注册任务
- PUT `/schedule` 时：取消旧任务 → 注册新任务（或停掉）
- 每次执行结果写 fetch_log；连续失败 5 次自动停用并标记 status=error
- 数据源工作空间隔离：定时任务执行时直接读 DB 中的 config，不依赖 `WorkspaceContext`

### JDBC 驱动依赖

`backend/pom.xml` 新增：

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <version>8.4.0</version>
    <scope>runtime</scope>
</dependency>
<!-- HikariCP 由 Spring Boot 默认引入；postgresql 已有 -->
```

## 前端组件

### 视图路由扩展

`App.vue` 视图状态从 `'welcome' | 'list' | 'graph' | 'settings'` 扩展为：

```ts
type View = 'welcome' | 'list' | 'graph' | 'settings' | 'datasource';
const currentDataSourceId = ref<string | null>(null);
```

切到其他视图时清空 `currentDataSourceId`。

### 侧栏改动（轻量）

复用现有 `WorkspaceNode.vue`，不改树结构：

- 旧条目（`kind=file/url`）：保持现有 hover 行为
- 新条目：
  - 不同 kind 显示不同图标（🗄 / 🐘 / 📄 / 🌐）
  - 右侧状态点（绿/红/灰）
  - 单击：打开详情视图
  - hover 显示连接信息

「📂 数据源」二级头右端新增「➕ 添加数据源」按钮（与"对话记录"`+` 对应位置）。

### `DataSourceCreateDialog.vue`

- 第一步：选择类型（4 张卡片）
- 第二步：按类型展示表单
- 底部：取消 / 保存

| 类型 | 表单字段 |
|---|---|
| MySQL/PgSQL | 名称、host、port（默认 3306/5432）、database、username、password、params（可选）、「测试连接」 |
| 文件上传 | 名称（默认填文件名）、拖拽 / 选择文件区、文件类型提示 |
| HTTPS | 名称、URL、方法、Headers（key-value 多行）、Body（仅 POST/PUT）、超时、定时拉取（开关 + 间隔）、「立即测试」 |

### `DataSourceDetailView.vue`（主区域）

按 kind 不同渲染不同 tab 集合：

**MySQL / PgSQL：**
```
[ℹ 概览] [📋 表列表] [📝 SQL 查询] [⚙ 配置]
```
- 概览：连接信息（密码遮蔽）、status、最后测试时间、「测试连接」
- 表列表：左侧表名 → 右侧前 50 行表格预览
- SQL：textarea + 「执行」按钮 + 结果表格（行数 / 耗时）
- 配置：编辑表单

**文件数据源：**
```
[ℹ 概览] [📖 内容预览] [⚙ 配置]
```
- 概览：文件名 / 大小 / 页数 / 字符数 / 「下载原文件」
- 内容预览：等宽字体显示文本，分段加载（每段 10000 字符）
- 配置：仅允许改"名称"

**HTTPS：**
```
[ℹ 概览] [▶ 执行] [📜 历史] [⏰ 定时] [⚙ 配置]
```
- 概览：URL / 方法 / 状态 / 最后执行时间
- 执行：左侧请求预览（只读）+「立即执行」 → 右侧响应（status / headers / body 美化）
- 历史：最近 20 条 fetch_log，点击展开响应体
- 定时：开关 + 间隔 + 下次执行预估
- 配置：编辑表单

### 前端文件清单

```
frontend/src/
├── components/
│   ├── Sidebar.vue                          [改]
│   ├── WorkspaceNode.vue                    [改]
│   ├── DataSourceCreateDialog.vue           [新]
│   └── datasource/
│       ├── DataSourceDetailView.vue         [新] 主容器
│       ├── DbOverviewTab.vue                [新]
│       ├── DbTableListTab.vue               [新]
│       ├── DbSqlTab.vue                     [新]
│       ├── FileContentTab.vue               [新]
│       ├── HttpExecuteTab.vue               [新]
│       ├── HttpHistoryTab.vue               [新]
│       ├── HttpScheduleTab.vue              [新]
│       └── DataSourceConfigForm.vue         [新] 各 kind 表单（创建 / 编辑复用）
├── api/
│   └── dataSources.ts                       [改]
├── composables/
│   ├── useSidebarTree.ts                    [改]
│   └── useDataSourceDetail.ts               [新]
└── types.ts                                 [改]
```

## 错误处理

| 场景 | 处理 |
|---|---|
| 数据库测试连接失败 | toast「连接失败：{err}」，写 status=error / last_error |
| SQL 不是 SELECT 类型 | 400 + 详细原因「仅允许只读查询语句」 |
| HTTPS 执行超时 | 写 fetch_log 失败行 + last_error；status=error |
| HTTPS 命中 SSRF 拒绝 | 400「目标地址不允许（本地 / 内网）」 |
| 文件上传超大 | 400「文件超过 X MB 限制」 |
| 删除数据源时磁盘文件不存在 | 警告日志，不阻断数据库删除 |
| 定时任务执行时数据源已删除 | 取消调度，跳过日志写入 |

## 测试

- 手测：4 类数据源各自创建 → 测试 → 预览 / 执行 → 编辑 → 删除全流程
- MySQL/PgSQL：本地起测试库，建张样表，预览前 50 行；写 SELECT/INSERT 验证只读护栏
- 文件：分别上传 PDF/TXT/MD，验证抽文本与下载原文件
- HTTPS：测公开 GET（如 `https://httpbin.org/get`）、POST JSON；开启 60 秒定时验证调度
- 边界：超大文件、超大响应、SSRF 命中（`http://localhost`）、定时间隔 < 60、活跃任务 > 20

## 工时估算

| 阶段 | 估算 |
|---|---|
| 后端：表结构 + 实体 / Repo / DTO | 0.5 天 |
| 后端：JdbcConnectorService（含表列表 / 预览 / SQL 护栏） | 1 天 |
| 后端：HttpConnectorService + Scheduler + fetch_log | 1 天 |
| 后端：FileStoredService（落盘 + 抽文本 + 下载） | 0.5 天 |
| 后端：Controller + 测试连接 + 联调 | 0.5 天 |
| 前端：CreateDialog + 各 kind 表单 | 1 天 |
| 前端：DetailView + 4 类 tab 组件 | 1.5 天 |
| 前端：侧栏图标 / 状态点 / 视图路由 | 0.5 天 |
| 联调 + 样式微调 | 0.5 天 |
| **合计** | **约 7 个工作日** |

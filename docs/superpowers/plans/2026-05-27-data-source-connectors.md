# 多类型数据源接入 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 `data_source` 表上扩展 4 类持续挂载的数据源（MySQL、PgSQL、文件、HTTPS 接口），让用户能通过 UI 创建、测试、预览、查询、定时拉取。

**Architecture:** 复用现有 `data_source` 表（向后兼容增列 `config_json` / `status` / `last_tested_at` / `last_error` / `updated_at`），按 `kind` 字段分发到 `JdbcConnectorService` / `HttpConnectorService` / `FileStoredService` 三个连接器。新增 `data_source_fetch_log` 表存 HTTPS 执行历史，Spring `TaskScheduler` 跑定时拉取。前端新增视图态 `datasource`，进入 `DataSourceDetailView` 按 kind 渲染不同 tab。

**Tech Stack:** Spring Boot 3.3、MyBatis-Plus 3.5.7、HikariCP（Spring Boot 内置）、MySQL Connector/J 8.4、PostgreSQL JDBC 42.7（已有）、Java HttpClient（已有）、Vue 3.5 + TypeScript 5.8

**Spec：** [docs/superpowers/specs/2026-05-27-data-source-connectors-design.md](../specs/2026-05-27-data-source-connectors-design.md)

**测试策略：** 本仓库尚无单元测试基础设施（无 `backend/src/test/`），全部任务以**手测脚本 + 启动后端 + curl/前端点击** 为验证手段。请勿临时引入 JUnit 框架。

---

## Task 1：扩展 `data_source` 表 + 新增 `data_source_fetch_log`

**Files:**
- Modify: `backend/src/main/resources/sql/init.sql`

- [ ] **Step 1：在现有 `data_source` 表块后追加 5 列 + 新建 fetch_log 表**

在 `init.sql` 第 332 行（`CREATE INDEX IF NOT EXISTS idx_data_source_ws_created` 之后）插入：

```sql
-- 5.5.1 数据源新增字段：持续挂载类型所需的配置 + 状态
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS config_json    TEXT;
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS status         VARCHAR(16) DEFAULT 'idle';
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS last_tested_at BIGINT;
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS last_error     TEXT;
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS updated_at     BIGINT;

-- 5.5.2 数据源执行历史（仅 https_api 写入）：单数据源最多保留 20 条
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

- [ ] **Step 2：手动执行迁移并验证**

在本地 psql 中重连数据库执行：

```bash
psql -U postgres -d tuiyan -f backend/src/main/resources/sql/init.sql
psql -U postgres -d tuiyan -c "\d data_source"
psql -U postgres -d tuiyan -c "\d data_source_fetch_log"
```

Expected：`data_source` 多 5 列；`data_source_fetch_log` 存在。

- [ ] **Step 3：提交**

```bash
git add backend/src/main/resources/sql/init.sql
git commit -m "feat(ds): 扩展 data_source 表 + 新增 fetch_log 表"
```

---

## Task 2：扩展 `DataSourcePO` + 新建 `DataSourceFetchLogPO`

**Files:**
- Modify: `backend/src/main/java/com/tuiyan/backend/entity/DataSourcePO.java`
- Create: `backend/src/main/java/com/tuiyan/backend/entity/DataSourceFetchLogPO.java`

- [ ] **Step 1：在 `DataSourcePO` 中追加 5 个字段及其 getter/setter**

打开 `DataSourcePO.java`，在 `createdAt` 字段后追加（保持原有顺序不动）：

```java
    /** 类型特定连接配置 JSON：mysql/pgsql/file_stored/https_api 各异；旧 kind 为 null */
    private String configJson;
    /** idle | connected | error */
    private String status;
    /** 上次连接测试时间戳（毫秒） */
    private Long lastTestedAt;
    /** 上次失败原因（用于前端 hover 展示） */
    private String lastError;
    /** 配置最近修改时间（毫秒） */
    private Long updatedAt;

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(Long lastTestedAt) { this.lastTestedAt = lastTestedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
```

- [ ] **Step 2：创建 `DataSourceFetchLogPO`**

```java
package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 数据源执行历史（仅 https_api 写入）。每次手动执行 / 定时拉取都追加一行。
 */
@TableName("data_source_fetch_log")
public class DataSourceFetchLogPO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String dataSourceId;
    private Long fetchedAt;
    private Integer statusCode;
    private Boolean success;
    private String responseBody;
    private String errorMsg;
    private Integer durationMs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(String dataSourceId) { this.dataSourceId = dataSourceId; }
    public Long getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Long fetchedAt) { this.fetchedAt = fetchedAt; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
}
```

- [ ] **Step 3：编译验证**

```bash
cd backend && mvn -q -o compile
```

Expected：编译通过，无 unresolved symbol。

- [ ] **Step 4：提交**

```bash
git add backend/src/main/java/com/tuiyan/backend/entity/
git commit -m "feat(ds): 扩展 DataSourcePO 字段 + 新增 DataSourceFetchLogPO"
```

---

## Task 3：新增 `DataSourceFetchLogMapper`

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/mapper/DataSourceFetchLogMapper.java`

- [ ] **Step 1：创建 mapper（仿照现有 `DataSourceMapper`）**

```java
package com.tuiyan.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tuiyan.backend.entity.DataSourceFetchLogPO;

public interface DataSourceFetchLogMapper extends BaseMapper<DataSourceFetchLogPO> {
}
```

- [ ] **Step 2：编译**

```bash
cd backend && mvn -q -o compile
```

- [ ] **Step 3：提交**

```bash
git add backend/src/main/java/com/tuiyan/backend/mapper/DataSourceFetchLogMapper.java
git commit -m "feat(ds): 新增 DataSourceFetchLogMapper"
```

---

## Task 4：JDBC 驱动依赖（MySQL）

**Files:**
- Modify: `backend/pom.xml`

- [ ] **Step 1：在 `<dependencies>` 中追加 mysql-connector-j**

在 `postgresql` 依赖块（约第 56-62 行）之后追加：

```xml
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <version>8.4.0</version>
            <scope>runtime</scope>
        </dependency>
```

- [ ] **Step 2：拉取依赖并编译**

```bash
cd backend && mvn -q -o compile || mvn -q compile
```

Expected：依赖成功下载，编译通过。

- [ ] **Step 3：提交**

```bash
git add backend/pom.xml
git commit -m "build(ds): 引入 mysql-connector-j 8.4.0"
```

---

## Task 5：DTO 类（创建请求 / 测试响应 / 表预览 / SQL 执行 / HTTP 执行）

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/model/dto/DataSourceCreateRequest.java`
- Create: `backend/src/main/java/com/tuiyan/backend/model/dto/DataSourceUpdateRequest.java`
- Create: `backend/src/main/java/com/tuiyan/backend/model/dto/DataSourceTestResponse.java`
- Create: `backend/src/main/java/com/tuiyan/backend/model/dto/TablePreviewResponse.java`
- Create: `backend/src/main/java/com/tuiyan/backend/model/dto/SqlExecuteRequest.java`
- Create: `backend/src/main/java/com/tuiyan/backend/model/dto/SqlExecuteResponse.java`
- Create: `backend/src/main/java/com/tuiyan/backend/model/dto/HttpExecuteResponse.java`
- Create: `backend/src/main/java/com/tuiyan/backend/model/dto/HttpScheduleRequest.java`

- [ ] **Step 1：`DataSourceCreateRequest`**

```java
package com.tuiyan.backend.model.dto;

import java.util.Map;

/** 创建活数据源请求：name + kind + config（具体字段按 kind 不同，原样存为 config_json）。 */
public class DataSourceCreateRequest {
    private String name;
    private String kind;
    private Map<String, Object> config;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
}
```

- [ ] **Step 2：`DataSourceUpdateRequest`**

```java
package com.tuiyan.backend.model.dto;

import java.util.Map;

/** 编辑活数据源：name 可改；config 可改（kind 不可变）。 */
public class DataSourceUpdateRequest {
    private String name;
    private Map<String, Object> config;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
}
```

- [ ] **Step 3：`DataSourceTestResponse`**

```java
package com.tuiyan.backend.model.dto;

/** 数据源连接测试结果：success + 消息（错误时填错误信息）+ 延迟。 */
public class DataSourceTestResponse {
    private boolean success;
    private String message;
    private Integer latencyMs;

    public DataSourceTestResponse() {}
    public DataSourceTestResponse(boolean success, String message, Integer latencyMs) {
        this.success = success; this.message = message; this.latencyMs = latencyMs;
    }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
}
```

- [ ] **Step 4：`TablePreviewResponse`**

```java
package com.tuiyan.backend.model.dto;

import java.util.List;

/** 表预览结果：列名 + 行数据（按列顺序排列的字符串值）+ 总行数（来自 LIMIT 截断前的 count）。 */
public class TablePreviewResponse {
    private List<String> columns;
    private List<List<Object>> rows;
    private int rowCount;
    private boolean truncated;

    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }
    public List<List<Object>> getRows() { return rows; }
    public void setRows(List<List<Object>> rows) { this.rows = rows; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
}
```

- [ ] **Step 5：`SqlExecuteRequest` + `SqlExecuteResponse`**

```java
// SqlExecuteRequest.java
package com.tuiyan.backend.model.dto;

public class SqlExecuteRequest {
    private String sql;
    private Integer limit;
    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }
    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
}
```

```java
// SqlExecuteResponse.java
package com.tuiyan.backend.model.dto;

import java.util.List;

public class SqlExecuteResponse {
    private List<String> columns;
    private List<List<Object>> rows;
    private int rowCount;
    private int durationMs;
    private boolean truncated;

    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }
    public List<List<Object>> getRows() { return rows; }
    public void setRows(List<List<Object>> rows) { this.rows = rows; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public int getDurationMs() { return durationMs; }
    public void setDurationMs(int durationMs) { this.durationMs = durationMs; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
}
```

- [ ] **Step 6：`HttpExecuteResponse`**

```java
package com.tuiyan.backend.model.dto;

import java.util.Map;

public class HttpExecuteResponse {
    private boolean success;
    private Integer statusCode;
    private Map<String, String> headers;
    private String body;
    private String errorMsg;
    private int durationMs;
    private boolean truncated;

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public int getDurationMs() { return durationMs; }
    public void setDurationMs(int durationMs) { this.durationMs = durationMs; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
}
```

- [ ] **Step 7：`HttpScheduleRequest`**

```java
package com.tuiyan.backend.model.dto;

/** 启停定时拉取：enabled + intervalSec（≥ 60）。 */
public class HttpScheduleRequest {
    private boolean enabled;
    private Integer intervalSec;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Integer getIntervalSec() { return intervalSec; }
    public void setIntervalSec(Integer intervalSec) { this.intervalSec = intervalSec; }
}
```

- [ ] **Step 8：编译 + 提交**

```bash
cd backend && mvn -q -o compile
git add backend/src/main/java/com/tuiyan/backend/model/dto/
git commit -m "feat(ds): 数据源相关 DTO 类"
```

---

## Task 6：暴露 `WebPageFetcher` 的 SSRF 校验 + 改造 `AppPaths` 支持子目录

**Files:**
- Modify: `backend/src/main/java/com/tuiyan/backend/support/WebPageFetcher.java`
- Modify: `backend/src/main/java/com/tuiyan/backend/config/AppPaths.java`

- [ ] **Step 1：把 `WebPageFetcher.parseAndValidate` 从 package-private 改为 public**

打开文件，定位第 96 行 `static URI parseAndValidate(String raw)`，改为：

```java
    public static URI parseAndValidate(String raw) {
```

- [ ] **Step 2：在 `AppPaths` 中新增 `datasourceFilesDir()`**

在 `prefsFile()` 方法之后追加：

```java
    /** 数据源原文件存储目录（PDF/TXT/MD 等 file_stored 类型的落盘根目录）。 */
    public java.io.File datasourceFilesDir() {
        java.io.File dir = new java.io.File(rootDir(), "datasource-files");
        if (!dir.exists()) {
            try { java.nio.file.Files.createDirectories(dir.toPath()); }
            catch (java.io.IOException e) {
                log.warn("Failed to create datasource-files dir: {}", e.toString());
            }
        }
        return dir;
    }
```

- [ ] **Step 3：编译 + 提交**

```bash
cd backend && mvn -q -o compile
git add backend/src/main/java/com/tuiyan/backend/support/WebPageFetcher.java \
        backend/src/main/java/com/tuiyan/backend/config/AppPaths.java
git commit -m "refactor(ds): 暴露 SSRF 校验 API + AppPaths 新增 datasourceFilesDir"
```

---

## Task 7：`JdbcConnectorService` —— 测试连接 + 列表 + 预览 + SQL 执行

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/service/connector/JdbcConnectorService.java`

- [ ] **Step 1：创建类骨架 + 常量 + 临时连接构造**

```java
package com.tuiyan.backend.service.connector;

import com.tuiyan.backend.model.dto.DataSourceTestResponse;
import com.tuiyan.backend.model.dto.SqlExecuteResponse;
import com.tuiyan.backend.model.dto.TablePreviewResponse;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * MySQL / PostgreSQL 通用连接器：每次操作建临时 HikariDataSource，用完即关，不缓存连接池。
 * <p>SQL 仅允许只读语句（SELECT/SHOW/DESC/DESCRIBE/EXPLAIN），强制 LIMIT 兜底。
 */
@Service
public class JdbcConnectorService {

    private static final Logger log = LoggerFactory.getLogger(JdbcConnectorService.class);

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;
    public static final int CONNECT_TIMEOUT_MS = 5_000;
    public static final int QUERY_TIMEOUT_SEC = 15;

    // 只读语句白名单：开头关键字（忽略大小写 + 前导空白）
    private static final Pattern READONLY_PATTERN =
            Pattern.compile("^\\s*(SELECT|SHOW|DESC|DESCRIBE|EXPLAIN)\\b.*",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 按 kind + config 构造一个独立、最小化的 HikariDataSource，调用方负责关。 */
    private DataSource buildTempDataSource(String kind, Map<String, Object> cfg) {
        String host = strOf(cfg, "host", "localhost");
        Object portObj = cfg.get("port");
        int port = portObj instanceof Number n ? n.intValue() : ("mysql".equals(kind) ? 3306 : 5432);
        String db = strOf(cfg, "database", "");
        String user = strOf(cfg, "username", "");
        String pwd = strOf(cfg, "password", "");
        String params = strOf(cfg, "params", "");

        String url;
        String driver;
        if ("mysql".equals(kind)) {
            url = "jdbc:mysql://" + host + ":" + port + "/" + db;
            if (!params.isBlank()) url += "?" + params;
            driver = "com.mysql.cj.jdbc.Driver";
        } else if ("pgsql".equals(kind)) {
            url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
            if (!params.isBlank()) url += "?" + params;
            driver = "org.postgresql.Driver";
        } else {
            throw new IllegalArgumentException("不支持的 kind: " + kind);
        }

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(url);
        hc.setUsername(user);
        hc.setPassword(pwd);
        hc.setDriverClassName(driver);
        hc.setMaximumPoolSize(2);
        hc.setMinimumIdle(0);
        hc.setConnectionTimeout(CONNECT_TIMEOUT_MS);
        hc.setIdleTimeout(10_000);
        hc.setPoolName("ds-temp-" + System.nanoTime());
        return new HikariDataSource(hc);
    }

    private static String strOf(Map<String, Object> m, String k, String dft) {
        Object v = m.get(k);
        return v == null ? dft : String.valueOf(v);
    }
}
```

- [ ] **Step 2：追加 `test` 方法**

```java
    /** 连接测试：建池 → getConnection → isValid(2s)；返回毫秒延迟或错误。 */
    public DataSourceTestResponse test(String kind, Map<String, Object> cfg) {
        long t0 = System.currentTimeMillis();
        try (HikariDataSource ds = (HikariDataSource) buildTempDataSource(kind, cfg);
             Connection conn = ds.getConnection()) {
            boolean ok = conn.isValid(2);
            int latency = (int) (System.currentTimeMillis() - t0);
            return ok
                    ? new DataSourceTestResponse(true, "ok", latency)
                    : new DataSourceTestResponse(false, "isValid 返回 false", latency);
        } catch (Exception e) {
            log.warn("[ds] jdbc test failed: {}", e.toString());
            return new DataSourceTestResponse(false, e.getMessage(), null);
        }
    }
```

- [ ] **Step 3：追加 `listTables` 方法**

```java
    /** 列出当前 catalog/schema 下的所有用户表。MySQL 读 information_schema；PgSQL 读 pg_catalog。 */
    public List<String> listTables(String kind, Map<String, Object> cfg) {
        try (HikariDataSource ds = (HikariDataSource) buildTempDataSource(kind, cfg);
             Connection conn = ds.getConnection()) {
            String sql;
            if ("mysql".equals(kind)) {
                sql = "SELECT TABLE_NAME FROM information_schema.tables " +
                      "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME";
            } else {
                sql = "SELECT tablename FROM pg_catalog.pg_tables " +
                      "WHERE schemaname NOT IN ('pg_catalog','information_schema') " +
                      "ORDER BY tablename";
            }
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("列出表失败: " + e.getMessage(), e);
        }
    }
```

- [ ] **Step 4：追加 `previewTable` + `executeSql` + 共享行读取工具**

```java
    /** 预览：SELECT * FROM `<table>` LIMIT N。table 名走白名单校验防 SQL 注入。 */
    public TablePreviewResponse previewTable(String kind, Map<String, Object> cfg,
                                             String table, int limit) {
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, MAX_LIMIT));
        String quoted = "mysql".equals(kind) ? "`" + table + "`" : "\"" + table + "\"";
        String sql = "SELECT * FROM " + quoted + " LIMIT " + safeLimit;
        SqlExecuteResponse r = executeSql(kind, cfg, sql, safeLimit);
        TablePreviewResponse out = new TablePreviewResponse();
        out.setColumns(r.getColumns());
        out.setRows(r.getRows());
        out.setRowCount(r.getRowCount());
        out.setTruncated(r.isTruncated());
        return out;
    }

    /** 执行只读 SQL：白名单校验 → 单语句校验 → 强制 LIMIT 兜底 → 受限超时执行。 */
    public SqlExecuteResponse executeSql(String kind, Map<String, Object> cfg,
                                         String rawSql, int limit) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new IllegalArgumentException("SQL 为空");
        }
        // 切掉末尾分号 + 拒绝多语句
        String sql = rawSql.trim();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
        if (sql.contains(";")) {
            throw new IllegalArgumentException("不允许多语句执行");
        }
        if (!READONLY_PATTERN.matcher(sql).matches()) {
            throw new IllegalArgumentException("仅允许只读查询语句 (SELECT/SHOW/DESC/EXPLAIN)");
        }
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        // SELECT 类语句没有 LIMIT 时追加
        boolean isSelect = sql.toUpperCase().startsWith("SELECT");
        boolean hasLimit = sql.toUpperCase().contains(" LIMIT ");
        if (isSelect && !hasLimit) sql = sql + " LIMIT " + safeLimit;

        long t0 = System.currentTimeMillis();
        try (HikariDataSource ds = (HikariDataSource) buildTempDataSource(kind, cfg);
             Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> cols = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) cols.add(meta.getColumnLabel(i));
                List<List<Object>> rows = new ArrayList<>();
                int n = 0;
                boolean truncated = false;
                while (rs.next()) {
                    if (n >= safeLimit) { truncated = true; break; }
                    List<Object> row = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        Object v = rs.getObject(i);
                        row.add(v == null ? null : (isSimple(v) ? v : String.valueOf(v)));
                    }
                    rows.add(row);
                    n++;
                }
                SqlExecuteResponse r = new SqlExecuteResponse();
                r.setColumns(cols);
                r.setRows(rows);
                r.setRowCount(rows.size());
                r.setDurationMs((int) (System.currentTimeMillis() - t0));
                r.setTruncated(truncated);
                return r;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 执行失败: " + e.getMessage(), e);
        }
    }

    private static boolean isSimple(Object v) {
        return v instanceof Number || v instanceof Boolean || v instanceof String;
    }
```

- [ ] **Step 5：编译**

```bash
cd backend && mvn -q -o compile
```

Expected：通过。

- [ ] **Step 6：提交**

```bash
git add backend/src/main/java/com/tuiyan/backend/service/connector/JdbcConnectorService.java
git commit -m "feat(ds): JdbcConnectorService 提供 MySQL/PgSQL 测试+预览+SQL 能力"
```

---

## Task 8：`HttpConnectorService` —— 同步执行 + 响应截断 + SSRF

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/service/connector/HttpConnectorService.java`

- [ ] **Step 1：创建类，封装 Java HttpClient 调用**

```java
package com.tuiyan.backend.service.connector;

import com.tuiyan.backend.model.dto.HttpExecuteResponse;
import com.tuiyan.backend.support.WebPageFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * HTTPS 接口数据源执行器：单次同步请求 + 响应截断 + SSRF 校验。
 * <p>响应体阈值：
 * <ul>
 *   <li>≤ 100 KB：完整入 fetch_log；</li>
 *   <li>100 KB ~ 1 MB：截断至 100 KB 入表，标 truncated；</li>
 *   <li>&gt; 1 MB：直接丢弃 body，errorMsg 标记。</li>
 * </ul>
 */
@Service
public class HttpConnectorService {

    private static final Logger log = LoggerFactory.getLogger(HttpConnectorService.class);
    public static final int DEFAULT_TIMEOUT_MS = 15_000;
    public static final int MAX_TIMEOUT_MS = 60_000;
    public static final int RESPONSE_KEEP_MAX = 100 * 1024;     // 100 KB 保留
    public static final int RESPONSE_DROP_THRESHOLD = 1024 * 1024; // 1 MB 丢弃

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public HttpExecuteResponse execute(Map<String, Object> cfg) {
        HttpExecuteResponse out = new HttpExecuteResponse();
        long t0 = System.currentTimeMillis();
        String url = String.valueOf(cfg.getOrDefault("url", ""));
        String method = String.valueOf(cfg.getOrDefault("method", "GET")).toUpperCase();
        Object timeoutObj = cfg.get("timeoutMs");
        int timeoutMs = timeoutObj instanceof Number n
                ? Math.min(Math.max(1000, n.intValue()), MAX_TIMEOUT_MS)
                : DEFAULT_TIMEOUT_MS;
        try {
            // SSRF 校验（复用现有 WebPageFetcher）
            URI uri = WebPageFetcher.parseAndValidate(url);
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMillis(timeoutMs));
            // headers
            Object hdrs = cfg.get("headers");
            if (hdrs instanceof Map<?, ?> mp) {
                for (Map.Entry<?, ?> e : mp.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    String v = String.valueOf(e.getValue());
                    if (!k.isBlank()) req.header(k, v);
                }
            }
            // body
            String body = String.valueOf(cfg.getOrDefault("body", ""));
            HttpRequest.BodyPublisher pub = body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            switch (method) {
                case "GET"    -> req.GET();
                case "DELETE" -> req.DELETE();
                case "POST"   -> req.POST(pub);
                case "PUT"    -> req.PUT(pub);
                default -> throw new IllegalArgumentException("不支持的方法: " + method);
            }
            HttpResponse<byte[]> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] respBytes = resp.body() == null ? new byte[0] : resp.body();
            out.setStatusCode(resp.statusCode());
            out.setSuccess(resp.statusCode() >= 200 && resp.statusCode() < 400);
            Map<String, String> headerMap = new LinkedHashMap<>();
            resp.headers().map().forEach((k, vs) -> headerMap.put(k, String.join(", ", vs)));
            out.setHeaders(headerMap);
            // body 截断逻辑
            if (respBytes.length > RESPONSE_DROP_THRESHOLD) {
                out.setBody(null);
                out.setErrorMsg("响应过大已丢弃（" + respBytes.length + " 字节，阈值 1 MB）");
                out.setTruncated(true);
            } else if (respBytes.length > RESPONSE_KEEP_MAX) {
                out.setBody(new String(respBytes, 0, RESPONSE_KEEP_MAX, StandardCharsets.UTF_8));
                out.setTruncated(true);
            } else {
                out.setBody(new String(respBytes, StandardCharsets.UTF_8));
                out.setTruncated(false);
            }
        } catch (IllegalArgumentException ie) {
            out.setSuccess(false);
            out.setErrorMsg(ie.getMessage());
        } catch (Exception e) {
            out.setSuccess(false);
            out.setErrorMsg(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("[ds] http execute failed url={} err={}", url, e.toString());
        } finally {
            out.setDurationMs((int) (System.currentTimeMillis() - t0));
        }
        return out;
    }
}
```

- [ ] **Step 2：编译 + 提交**

```bash
cd backend && mvn -q -o compile
git add backend/src/main/java/com/tuiyan/backend/service/connector/HttpConnectorService.java
git commit -m "feat(ds): HttpConnectorService 同步执行 + 响应截断 + SSRF"
```

---

## Task 9：`FileStoredService` —— 文件落盘 + 抽文本

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/service/connector/FileStoredService.java`

- [ ] **Step 1：创建类，封装 PDF/TXT/MD 落盘 + 抽文本逻辑**

```java
package com.tuiyan.backend.service.connector;

import com.tuiyan.backend.config.AppPaths;
import com.tuiyan.backend.support.FileSniffer;
import com.tuiyan.backend.support.PdfTextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件数据源服务：PDF/TXT/MD 落盘到 ~/.tuiyan/datasource-files/&lt;id&gt;/ 下，
 * 同步抽出纯文本旁挂 .txt。
 */
@Service
public class FileStoredService {

    private static final Logger log = LoggerFactory.getLogger(FileStoredService.class);
    public static final long PDF_LIMIT_BYTES = 12L * 1024 * 1024;
    public static final long TEXT_LIMIT_BYTES = 4L * 1024 * 1024;
    public static final int TEXT_CHAR_BUDGET = 200_000;

    private final AppPaths paths;

    public FileStoredService(AppPaths paths) { this.paths = paths; }

    /**
     * 接收上传，落盘并抽文本。
     * @return config_json 的内容（storagePath / extractedTextPath / chars / pages）
     */
    public Map<String, Object> ingest(String dataSourceId, MultipartFile mf) throws IOException {
        String safe = FileSniffer.sanitizeFilename(mf.getOriginalFilename());
        String lname = safe.toLowerCase();
        byte[] head = FileSniffer.readHead(mf, 12);

        boolean isPdf = FileSniffer.isPdfMagic(head) || lname.endsWith(".pdf");
        boolean isTxt = lname.endsWith(".txt") || lname.endsWith(".md");
        if (!isPdf && !isTxt) {
            throw new IllegalArgumentException("仅支持 PDF / TXT / MD 文件");
        }
        long size = mf.getSize();
        if (isPdf && size > PDF_LIMIT_BYTES) {
            throw new IllegalArgumentException("PDF 超过 " + (PDF_LIMIT_BYTES / 1024 / 1024) + " MB 限制");
        }
        if (isTxt && size > TEXT_LIMIT_BYTES) {
            throw new IllegalArgumentException("TXT/MD 超过 " + (TEXT_LIMIT_BYTES / 1024 / 1024) + " MB 限制");
        }

        // 子目录：datasource-files/<id>/
        File baseDir = paths.datasourceFilesDir();
        File targetDir = new File(baseDir, dataSourceId);
        Files.createDirectories(targetDir.toPath());

        File rawFile = new File(targetDir, safe);
        try (var in = mf.getInputStream()) {
            Files.copy(in, rawFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        String text;
        int pages = 0;
        if (isPdf) {
            try (PDDocument doc = Loader.loadPDF(rawFile)) {
                pages = doc.getNumberOfPages();
                text = PdfTextExtractor.extractText(doc);
            } catch (Exception e) {
                log.warn("PDF 抽文本失败: {}", e.toString());
                text = "";
            }
        } else {
            text = Files.readString(rawFile.toPath(), StandardCharsets.UTF_8);
        }
        if (text != null && text.length() > TEXT_CHAR_BUDGET) {
            text = text.substring(0, TEXT_CHAR_BUDGET) + "\n[…truncated…]";
        }
        int chars = text == null ? 0 : text.length();

        // 旁挂 .txt
        File txtFile = new File(targetDir, safe + ".txt");
        Files.writeString(txtFile.toPath(), text == null ? "" : text, StandardCharsets.UTF_8);

        // 相对路径写入 config_json（防止根目录漂移）
        String rel = "datasource-files/" + dataSourceId + "/" + safe;
        String relTxt = "datasource-files/" + dataSourceId + "/" + safe + ".txt";

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("storagePath", rel);
        cfg.put("extractedTextPath", relTxt);
        cfg.put("chars", chars);
        cfg.put("pages", pages);
        cfg.put("originalName", safe);
        cfg.put("sizeBytes", size);
        return cfg;
    }

    /** 读取已抽取的文本片段（按字符偏移 + 长度）。 */
    public String readText(Map<String, Object> cfg, int offset, int length) throws IOException {
        String rel = String.valueOf(cfg.getOrDefault("extractedTextPath", ""));
        if (rel.isBlank()) return "";
        File f = new File(paths.rootDir(), rel);
        if (!f.exists()) return "";
        String all = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        int start = Math.max(0, Math.min(offset, all.length()));
        int end = Math.min(all.length(), start + Math.max(1, length));
        return all.substring(start, end);
    }

    /** 取原文件 File 对象供下载使用。文件不存在时返回 null。 */
    public File originalFile(Map<String, Object> cfg) {
        String rel = String.valueOf(cfg.getOrDefault("storagePath", ""));
        if (rel.isBlank()) return null;
        File f = new File(paths.rootDir(), rel);
        return f.exists() ? f : null;
    }

    /** 删除数据源时级联清理：删整个 datasource-files/<id>/ 目录。 */
    public void deleteFiles(String dataSourceId) {
        File dir = new File(paths.datasourceFilesDir(), dataSourceId);
        if (!dir.exists()) return;
        try (var stream = Files.walk(dir.toPath())) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                  .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException e) {
            log.warn("clean datasource files failed: {}", e.toString());
        }
    }
}
```

- [ ] **Step 2：编译 + 提交**

```bash
cd backend && mvn -q -o compile
git add backend/src/main/java/com/tuiyan/backend/service/connector/FileStoredService.java
git commit -m "feat(ds): FileStoredService 落盘 PDF/TXT/MD + 抽文本"
```

---

## Task 10：扩展 `DataSourceRepository`

**Files:**
- Modify: `backend/src/main/java/com/tuiyan/backend/repository/DataSourceRepository.java`

- [ ] **Step 1：在类中追加 CRUD 方法**

在现有 `saveSource(...)` 方法之后追加：

```java
    /** 创建活数据源（mysql/pgsql/file_stored/https_api），允许显式 id。 */
    @Transactional
    public DataSourcePO create(String id, String kind, String name, String mime, Long sizeBytes,
                               Map<String, Object> config) {
        DataSourcePO po = new DataSourcePO();
        po.setId(id != null ? id : ("ds_" + System.currentTimeMillis()
                + "_" + Long.toString(System.nanoTime() & 0xffff, 36)));
        po.setWorkspaceId(WorkspaceContext.required());
        po.setKind(kind);
        po.setName(name);
        po.setMime(mime);
        po.setSizeBytes(sizeBytes);
        po.setStatus("idle");
        po.setCreatedAt(System.currentTimeMillis());
        po.setUpdatedAt(po.getCreatedAt());
        if (config != null && !config.isEmpty()) {
            po.setConfigJson(codec.toJson(config));
        }
        mapper.insert(po);
        return po;
    }

    /** 按 id 取一行（不做工作空间隔离，调用方自行校验）。 */
    public DataSourcePO findById(String id) {
        return mapper.selectById(id);
    }

    /** 解析 configJson 为 Map（兜底返回空 Map）。 */
    public Map<String, Object> readConfig(DataSourcePO po) {
        if (po == null || po.getConfigJson() == null || po.getConfigJson().isBlank()) {
            return new LinkedHashMap<>();
        }
        Object parsed = codec.readValue(po.getConfigJson(), Object.class);
        return parsed instanceof Map<?, ?> mp ? toStringMap(mp) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringMap(Map<?, ?> mp) {
        Map<String, Object> out = new LinkedHashMap<>();
        mp.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    @Transactional
    public boolean updateConfig(String id, String newName, Map<String, Object> newConfig) {
        DataSourcePO po = mapper.selectById(id);
        if (po == null) return false;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return false;
        if (newName != null && !newName.isBlank()) po.setName(newName);
        if (newConfig != null) po.setConfigJson(codec.toJson(newConfig));
        po.setUpdatedAt(System.currentTimeMillis());
        return mapper.updateById(po) > 0;
    }

    @Transactional
    public void markStatus(String id, String status, String errorMsg) {
        DataSourcePO po = mapper.selectById(id);
        if (po == null) return;
        po.setStatus(status);
        po.setLastTestedAt(System.currentTimeMillis());
        po.setLastError(errorMsg);
        mapper.updateById(po);
    }

    /** 跨工作空间的全量加载，用于启动期调度器扫描所有 https_api 任务。 */
    public List<DataSourcePO> listAllByKind(String kind) {
        return mapper.selectList(new LambdaQueryWrapper<DataSourcePO>()
                .eq(DataSourcePO::getKind, kind));
    }
```

- [ ] **Step 2：toMap 中补充新列回显**

定位 `toMap(DataSourcePO po)` 方法，把方法体改为（保留原顺序的同时追加新字段）：

```java
    private Map<String, Object> toMap(DataSourcePO po) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", po.getId());
        out.put("kind", po.getKind());
        out.put("name", po.getName());
        if (po.getMime() != null) out.put("mime", po.getMime());
        if (po.getSizeBytes() != null) out.put("size", po.getSizeBytes());
        out.put("createdAt", po.getCreatedAt());
        if (po.getStatus() != null)        out.put("status", po.getStatus());
        if (po.getLastTestedAt() != null)  out.put("lastTestedAt", po.getLastTestedAt());
        if (po.getLastError() != null)     out.put("lastError", po.getLastError());
        if (po.getUpdatedAt() != null)     out.put("updatedAt", po.getUpdatedAt());
        if (po.getConfigJson() != null && !po.getConfigJson().isBlank()) {
            Object cfg = codec.readValue(po.getConfigJson(), Object.class);
            if (cfg instanceof Map<?, ?> mp) {
                Map<String, Object> safe = maskSensitive(toStringMap(mp));
                out.put("config", safe);
            }
        }
        if (po.getExtraJson() != null && !po.getExtraJson().isBlank()) {
            Object extra = codec.readValue(po.getExtraJson(), Object.class);
            if (extra instanceof Map<?, ?> mp) {
                for (Map.Entry<?, ?> e : mp.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (!out.containsKey(k)) out.put(k, e.getValue());
                }
            }
        }
        return out;
    }

    /** 列表返回时把 password/Authorization 等敏感字段遮蔽。编辑表单单独调 /detail 端点拿原文。 */
    private static Map<String, Object> maskSensitive(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>(cfg);
        if (out.containsKey("password")) out.put("password", "********");
        Object hdrs = out.get("headers");
        if (hdrs instanceof Map<?, ?> mp) {
            Map<String, Object> mh = new LinkedHashMap<>();
            mp.forEach((k, v) -> {
                String kk = String.valueOf(k);
                if (kk.equalsIgnoreCase("authorization") || kk.toLowerCase().contains("token")
                        || kk.toLowerCase().contains("apikey") || kk.toLowerCase().contains("api-key")) {
                    mh.put(kk, "********");
                } else {
                    mh.put(kk, v);
                }
            });
            out.put("headers", mh);
        }
        return out;
    }
```

- [ ] **Step 3：编译**

```bash
cd backend && mvn -q -o compile
```

- [ ] **Step 4：提交**

```bash
git add backend/src/main/java/com/tuiyan/backend/repository/DataSourceRepository.java
git commit -m "feat(ds): DataSourceRepository 支持活数据源 CRUD + 敏感字段遮蔽"
```

---

## Task 11：`DataSourceFetchLogRepository`

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/repository/DataSourceFetchLogRepository.java`

- [ ] **Step 1：创建仓储**

```java
package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.entity.DataSourceFetchLogPO;
import com.tuiyan.backend.mapper.DataSourceFetchLogMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * HTTPS 数据源执行历史仓储：写入后清理超出 20 条的旧日志。
 */
@Repository
public class DataSourceFetchLogRepository {

    public static final int KEEP_LATEST = 20;
    private final DataSourceFetchLogMapper mapper;

    public DataSourceFetchLogRepository(DataSourceFetchLogMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public DataSourceFetchLogPO append(String dataSourceId, Integer statusCode, boolean success,
                                       String responseBody, String errorMsg, int durationMs) {
        DataSourceFetchLogPO po = new DataSourceFetchLogPO();
        po.setDataSourceId(dataSourceId);
        po.setFetchedAt(System.currentTimeMillis());
        po.setStatusCode(statusCode);
        po.setSuccess(success);
        po.setResponseBody(responseBody);
        po.setErrorMsg(errorMsg);
        po.setDurationMs(durationMs);
        mapper.insert(po);
        trim(dataSourceId);
        return po;
    }

    public List<DataSourceFetchLogPO> listLatest(String dataSourceId) {
        return mapper.selectList(new LambdaQueryWrapper<DataSourceFetchLogPO>()
                .eq(DataSourceFetchLogPO::getDataSourceId, dataSourceId)
                .orderByDesc(DataSourceFetchLogPO::getFetchedAt)
                .last("LIMIT " + KEEP_LATEST));
    }

    /** 仅保留最近 KEEP_LATEST 条，多余删除。 */
    private void trim(String dataSourceId) {
        List<DataSourceFetchLogPO> all = mapper.selectList(
                new LambdaQueryWrapper<DataSourceFetchLogPO>()
                        .eq(DataSourceFetchLogPO::getDataSourceId, dataSourceId)
                        .orderByDesc(DataSourceFetchLogPO::getFetchedAt));
        if (all.size() <= KEEP_LATEST) return;
        for (int i = KEEP_LATEST; i < all.size(); i++) {
            mapper.deleteById(all.get(i).getId());
        }
    }
}
```

- [ ] **Step 2：编译 + 提交**

```bash
cd backend && mvn -q -o compile
git add backend/src/main/java/com/tuiyan/backend/repository/DataSourceFetchLogRepository.java
git commit -m "feat(ds): DataSourceFetchLogRepository 含滚动清理"
```

---

## Task 12：`HttpScheduler` —— 定时拉取调度

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/service/connector/HttpScheduler.java`

- [ ] **Step 1：创建调度器**

```java
package com.tuiyan.backend.service.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.model.dto.HttpExecuteResponse;
import com.tuiyan.backend.repository.DataSourceFetchLogRepository;
import com.tuiyan.backend.repository.DataSourceRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * https_api 数据源定时拉取调度器。单线程池避免阻塞推演线程；
 * 每个数据源最多注册一个任务；连续失败 5 次自动停用。
 */
@Service
@EnableScheduling
public class HttpScheduler {

    private static final Logger log = LoggerFactory.getLogger(HttpScheduler.class);
    public static final int MIN_INTERVAL_SEC = 60;
    public static final int MAX_ACTIVE_TASKS = 20;
    public static final int MAX_CONSECUTIVE_FAILS = 5;

    private final ThreadPoolTaskScheduler scheduler;
    private final DataSourceRepository repo;
    private final DataSourceFetchLogRepository logRepo;
    private final HttpConnectorService http;
    private final ObjectMapper mapper;

    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final Map<String, Integer> failCounters = new ConcurrentHashMap<>();

    public HttpScheduler(DataSourceRepository repo,
                         DataSourceFetchLogRepository logRepo,
                         HttpConnectorService http,
                         ObjectMapper mapper) {
        this.repo = repo;
        this.logRepo = logRepo;
        this.http = http;
        this.mapper = mapper;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(2);
        this.scheduler.setThreadNamePrefix("ds-http-");
        this.scheduler.setDaemon(true);
        this.scheduler.initialize();
    }

    /** 启动时加载 DB 中所有 https_api 且 schedule.enabled=true 的记录注册任务。 */
    @PostConstruct
    public void bootstrap() {
        for (DataSourcePO po : repo.listAllByKind("https_api")) {
            Map<String, Object> cfg = repo.readConfig(po);
            Map<?, ?> sched = cfg.get("schedule") instanceof Map<?, ?> m ? m : null;
            if (sched == null) continue;
            Object enabled = sched.get("enabled");
            Object interval = sched.get("intervalSec");
            if (Boolean.TRUE.equals(enabled) && interval instanceof Number n) {
                try {
                    register(po.getId(), n.intValue());
                } catch (Exception e) {
                    log.warn("[ds-sched] bootstrap register failed id={} err={}", po.getId(), e.toString());
                }
            }
        }
    }

    /** 注册或更新某数据源的定时任务。已存在则先取消。 */
    public synchronized void register(String dataSourceId, int intervalSec) {
        if (intervalSec < MIN_INTERVAL_SEC) {
            throw new IllegalArgumentException("间隔不得小于 " + MIN_INTERVAL_SEC + " 秒");
        }
        if (!tasks.containsKey(dataSourceId) && tasks.size() >= MAX_ACTIVE_TASKS) {
            throw new IllegalStateException("活跃定时任务已达上限 " + MAX_ACTIVE_TASKS);
        }
        cancel(dataSourceId);
        Runnable r = () -> runOnce(dataSourceId);
        ScheduledFuture<?> f = scheduler.scheduleAtFixedRate(
                r, Instant.now().plusSeconds(intervalSec), Duration.ofSeconds(intervalSec));
        tasks.put(dataSourceId, f);
        failCounters.put(dataSourceId, 0);
        log.info("[ds-sched] registered id={} every {}s", dataSourceId, intervalSec);
    }

    public synchronized void cancel(String dataSourceId) {
        ScheduledFuture<?> f = tasks.remove(dataSourceId);
        if (f != null) f.cancel(false);
        failCounters.remove(dataSourceId);
    }

    public int activeCount() { return tasks.size(); }

    /** 一次执行 + 落 fetch_log + 失败计数。供注册任务和"手动立即执行"复用。 */
    public HttpExecuteResponse runOnce(String dataSourceId) {
        DataSourcePO po = repo.findById(dataSourceId);
        if (po == null) {
            cancel(dataSourceId);
            return null;
        }
        Map<String, Object> cfg = repo.readConfig(po);
        HttpExecuteResponse r = http.execute(cfg);
        logRepo.append(dataSourceId, r.getStatusCode(), r.isSuccess(),
                r.getBody(), r.getErrorMsg(), r.getDurationMs());
        if (r.isSuccess()) {
            failCounters.put(dataSourceId, 0);
            repo.markStatus(dataSourceId, "connected", null);
        } else {
            int n = failCounters.getOrDefault(dataSourceId, 0) + 1;
            failCounters.put(dataSourceId, n);
            repo.markStatus(dataSourceId, "error", r.getErrorMsg());
            if (n >= MAX_CONSECUTIVE_FAILS) {
                log.warn("[ds-sched] auto-disable id={} after {} fails", dataSourceId, n);
                // 落配置 enabled=false
                Map<?, ?> sched = cfg.get("schedule") instanceof Map<?, ?> m ? m : null;
                if (sched != null) {
                    var newCfg = new java.util.LinkedHashMap<String, Object>(cfg);
                    var newSched = new java.util.LinkedHashMap<>(sched);
                    newSched.put("enabled", false);
                    newCfg.put("schedule", newSched);
                    repo.updateConfig(po.getId(), null, newCfg);
                }
                cancel(dataSourceId);
            }
        }
        return r;
    }
}
```

- [ ] **Step 2：编译 + 提交**

```bash
cd backend && mvn -q -o compile
git add backend/src/main/java/com/tuiyan/backend/service/connector/HttpScheduler.java
git commit -m "feat(ds): HttpScheduler 定时拉取 + 连续失败自动停用"
```

---

## Task 13：`DataSourceService` —— 统一对外门面

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/service/DataSourceService.java`

- [ ] **Step 1：创建服务**

```java
package com.tuiyan.backend.service;

import com.tuiyan.backend.entity.DataSourceFetchLogPO;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.model.dto.*;
import com.tuiyan.backend.repository.DataSourceFetchLogRepository;
import com.tuiyan.backend.repository.DataSourceRepository;
import com.tuiyan.backend.service.connector.FileStoredService;
import com.tuiyan.backend.service.connector.HttpScheduler;
import com.tuiyan.backend.service.connector.HttpConnectorService;
import com.tuiyan.backend.service.connector.JdbcConnectorService;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * 数据源门面：负责按 kind 分发到 Jdbc/Http/FileStored 连接器，串联调度器。
 * Controller 只与本类交互；Repository 不直接暴露给 Controller。
 */
@Service
public class DataSourceService {

    private final DataSourceRepository repo;
    private final DataSourceFetchLogRepository logRepo;
    private final JdbcConnectorService jdbc;
    private final HttpConnectorService http;
    private final HttpScheduler scheduler;
    private final FileStoredService fileStored;

    public DataSourceService(DataSourceRepository repo,
                             DataSourceFetchLogRepository logRepo,
                             JdbcConnectorService jdbc,
                             HttpConnectorService http,
                             HttpScheduler scheduler,
                             FileStoredService fileStored) {
        this.repo = repo;
        this.logRepo = logRepo;
        this.jdbc = jdbc;
        this.http = http;
        this.scheduler = scheduler;
        this.fileStored = fileStored;
    }

    // ---------- 通用 CRUD ----------

    public Map<String, Object> create(DataSourceCreateRequest req) {
        String kind = req.getKind();
        validateKind(kind);
        if (kind.equals("file_stored")) {
            throw new IllegalArgumentException("file_stored 请走 /api/data-sources/file 上传端点");
        }
        DataSourcePO po = repo.create(null, kind, req.getName(), null, null, req.getConfig());
        // https_api 若 schedule.enabled=true，立即注册
        if ("https_api".equals(kind)) tryScheduleFromConfig(po.getId(), req.getConfig());
        return findFull(po.getId());
    }

    public Map<String, Object> update(String id, DataSourceUpdateRequest req) {
        DataSourcePO po = ensureOwnership(id);
        boolean ok = repo.updateConfig(id, req.getName(), req.getConfig());
        if (!ok) throw new IllegalStateException("更新失败");
        if ("https_api".equals(po.getKind())) {
            scheduler.cancel(id);
            tryScheduleFromConfig(id, req.getConfig());
        }
        return findFull(id);
    }

    public boolean delete(String id) {
        DataSourcePO po = repo.findById(id);
        if (po == null) return false;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return false;
        scheduler.cancel(id);
        if ("file_stored".equals(po.getKind())) fileStored.deleteFiles(id);
        return repo.delete(id);
    }

    /** 含 config 全字段的单条详情（不遮蔽，专供编辑表单用）。 */
    public Map<String, Object> findFull(String id) {
        DataSourcePO po = ensureOwnership(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", po.getId());
        out.put("kind", po.getKind());
        out.put("name", po.getName());
        out.put("status", po.getStatus());
        out.put("lastTestedAt", po.getLastTestedAt());
        out.put("lastError", po.getLastError());
        out.put("createdAt", po.getCreatedAt());
        out.put("updatedAt", po.getUpdatedAt());
        out.put("config", repo.readConfig(po));
        return out;
    }

    // ---------- 连接测试 ----------

    public DataSourceTestResponse test(String id) {
        DataSourcePO po = ensureOwnership(id);
        DataSourceTestResponse r = dispatchTest(po);
        repo.markStatus(id, r.isSuccess() ? "connected" : "error",
                r.isSuccess() ? null : r.getMessage());
        return r;
    }

    /** 创建前的"立即测试"：不入库，仅用 kind+config 调连接器。 */
    public DataSourceTestResponse testInline(String kind, Map<String, Object> config) {
        validateKind(kind);
        return switch (kind) {
            case "mysql", "pgsql" -> jdbc.test(kind, config);
            case "https_api" -> {
                HttpExecuteResponse r = http.execute(config);
                yield new DataSourceTestResponse(
                        r.isSuccess(),
                        r.isSuccess() ? "HTTP " + r.getStatusCode() : r.getErrorMsg(),
                        r.getDurationMs());
            }
            default -> new DataSourceTestResponse(false, "该 kind 不支持测试", null);
        };
    }

    private DataSourceTestResponse dispatchTest(DataSourcePO po) {
        Map<String, Object> cfg = repo.readConfig(po);
        return switch (po.getKind()) {
            case "mysql", "pgsql" -> jdbc.test(po.getKind(), cfg);
            case "https_api" -> {
                HttpExecuteResponse r = http.execute(cfg);
                yield new DataSourceTestResponse(
                        r.isSuccess(),
                        r.isSuccess() ? "HTTP " + r.getStatusCode() : r.getErrorMsg(),
                        r.getDurationMs());
            }
            default -> new DataSourceTestResponse(true, "无需测试", 0);
        };
    }

    // ---------- 数据库专用 ----------

    public List<String> listTables(String id) {
        DataSourcePO po = ensureOwnership(id);
        requireKindIn(po, "mysql", "pgsql");
        return jdbc.listTables(po.getKind(), repo.readConfig(po));
    }

    public TablePreviewResponse previewTable(String id, String table, int limit) {
        DataSourcePO po = ensureOwnership(id);
        requireKindIn(po, "mysql", "pgsql");
        return jdbc.previewTable(po.getKind(), repo.readConfig(po), table, limit);
    }

    public SqlExecuteResponse executeSql(String id, SqlExecuteRequest req) {
        DataSourcePO po = ensureOwnership(id);
        requireKindIn(po, "mysql", "pgsql");
        int limit = req.getLimit() == null ? JdbcConnectorService.DEFAULT_LIMIT : req.getLimit();
        return jdbc.executeSql(po.getKind(), repo.readConfig(po), req.getSql(), limit);
    }

    // ---------- 文件专用 ----------

    public Map<String, Object> ingestFile(String name, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("文件为空");
        String displayName = (name == null || name.isBlank()) ? file.getOriginalFilename() : name;
        // 先建一行拿 id，再调 ingest（id 用作子目录名）
        DataSourcePO po = repo.create(null, "file_stored", displayName,
                file.getContentType(), file.getSize(), new LinkedHashMap<>());
        Map<String, Object> cfg = fileStored.ingest(po.getId(), file);
        repo.updateConfig(po.getId(), null, cfg);
        repo.markStatus(po.getId(), "connected", null);
        return findFull(po.getId());
    }

    public String readFileText(String id, int offset, int length) throws IOException {
        DataSourcePO po = ensureOwnership(id);
        requireKindIn(po, "file_stored");
        return fileStored.readText(repo.readConfig(po), offset, length);
    }

    public java.io.File originalFileFor(String id) {
        DataSourcePO po = ensureOwnership(id);
        requireKindIn(po, "file_stored");
        return fileStored.originalFile(repo.readConfig(po));
    }

    // ---------- HTTPS 专用 ----------

    public HttpExecuteResponse executeHttp(String id) {
        DataSourcePO po = ensureOwnership(id);
        requireKindIn(po, "https_api");
        return scheduler.runOnce(id);
    }

    public List<DataSourceFetchLogPO> listLogs(String id) {
        ensureOwnership(id);
        return logRepo.listLatest(id);
    }

    public void schedule(String id, HttpScheduleRequest req) {
        DataSourcePO po = ensureOwnership(id);
        requireKindIn(po, "https_api");
        Map<String, Object> cfg = repo.readConfig(po);
        Map<String, Object> sched = new LinkedHashMap<>();
        sched.put("enabled", req.isEnabled());
        if (req.getIntervalSec() != null) sched.put("intervalSec", req.getIntervalSec());
        else if (cfg.get("schedule") instanceof Map<?, ?> old && old.get("intervalSec") instanceof Number n) {
            sched.put("intervalSec", n.intValue());
        }
        Map<String, Object> newCfg = new LinkedHashMap<>(cfg);
        newCfg.put("schedule", sched);
        repo.updateConfig(id, null, newCfg);

        scheduler.cancel(id);
        if (req.isEnabled() && sched.get("intervalSec") instanceof Number ns) {
            scheduler.register(id, ns.intValue());
        }
    }

    private void tryScheduleFromConfig(String id, Map<String, Object> cfg) {
        if (cfg == null) return;
        Object schedObj = cfg.get("schedule");
        if (!(schedObj instanceof Map<?, ?> sched)) return;
        if (!Boolean.TRUE.equals(sched.get("enabled"))) return;
        Object iv = sched.get("intervalSec");
        if (iv instanceof Number n) scheduler.register(id, n.intValue());
    }

    // ---------- 通用辅助 ----------

    private static final Set<String> ALLOWED_KINDS =
            Set.of("mysql", "pgsql", "file_stored", "https_api");

    private static void validateKind(String kind) {
        if (!ALLOWED_KINDS.contains(kind)) {
            throw new IllegalArgumentException("不支持的 kind: " + kind);
        }
    }

    private DataSourcePO ensureOwnership(String id) {
        DataSourcePO po = repo.findById(id);
        if (po == null) throw new IllegalArgumentException("数据源不存在: " + id);
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) {
            throw new IllegalArgumentException("数据源不属于当前工作空间");
        }
        return po;
    }

    private static void requireKindIn(DataSourcePO po, String... kinds) {
        for (String k : kinds) if (k.equals(po.getKind())) return;
        throw new IllegalArgumentException("当前数据源 kind=" + po.getKind() + " 不支持该操作");
    }
}
```

- [ ] **Step 2：编译 + 提交**

```bash
cd backend && mvn -q -o compile
git add backend/src/main/java/com/tuiyan/backend/service/DataSourceService.java
git commit -m "feat(ds): DataSourceService 统一门面"
```

---

## Task 14：扩展 `DataSourceController` —— 所有新端点

**Files:**
- Modify: `backend/src/main/java/com/tuiyan/backend/controller/DataSourceController.java`

- [ ] **Step 1：替换整个文件内容**

```java
package com.tuiyan.backend.controller;

import com.tuiyan.backend.entity.DataSourceFetchLogPO;
import com.tuiyan.backend.model.dto.*;
import com.tuiyan.backend.repository.DataSourceRepository;
import com.tuiyan.backend.service.DataSourceService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 数据源端点：列表 / 创建 / 编辑 / 删除 / 测试 / 数据库专用 / 文件专用 / HTTPS 专用。
 * 创建路径 file_stored 走 /api/data-sources/file（multipart）；
 * 其他 kind 走 POST /api/data-sources（JSON）。
 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {

    private final DataSourceRepository repo;
    private final DataSourceService service;

    public DataSourceController(DataSourceRepository repo, DataSourceService service) {
        this.repo = repo;
        this.service = service;
    }

    // ---------- 列表 / CRUD ----------

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam(required = false) String workspaceId,
                                                          @RequestParam(required = false) String kind) {
        List<Map<String, Object>> all = (workspaceId != null && !workspaceId.isBlank())
                ? repo.list(workspaceId) : repo.list();
        if (kind != null && !kind.isBlank()) {
            all = all.stream().filter(m -> kind.equals(m.get("kind"))).toList();
        }
        return ResponseEntity.ok(all);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String id) {
        return ResponseEntity.ok(service.findFull(id));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody DataSourceCreateRequest req) {
        return ResponseEntity.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id,
                                                      @RequestBody DataSourceUpdateRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = service.delete(id);
        return ResponseEntity.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<DataSourceTestResponse> test(@PathVariable String id) {
        return ResponseEntity.ok(service.test(id));
    }

    @PostMapping("/test-inline")
    public ResponseEntity<DataSourceTestResponse> testInline(@RequestBody DataSourceCreateRequest req) {
        return ResponseEntity.ok(service.testInline(req.getKind(), req.getConfig()));
    }

    // ---------- 数据库专用 ----------

    @GetMapping("/{id}/tables")
    public ResponseEntity<List<String>> tables(@PathVariable String id) {
        return ResponseEntity.ok(service.listTables(id));
    }

    @GetMapping("/{id}/tables/{name}/preview")
    public ResponseEntity<TablePreviewResponse> tablePreview(@PathVariable String id,
                                                             @PathVariable String name,
                                                             @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(service.previewTable(id, name, limit));
    }

    @PostMapping("/{id}/sql")
    public ResponseEntity<SqlExecuteResponse> sql(@PathVariable String id,
                                                  @RequestBody SqlExecuteRequest req) {
        return ResponseEntity.ok(service.executeSql(id, req));
    }

    // ---------- 文件专用 ----------

    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file,
                                                          @RequestParam(value = "name", required = false) String name)
            throws IOException {
        return ResponseEntity.ok(service.ingestFile(name, file));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<String> content(@PathVariable String id,
                                          @RequestParam(defaultValue = "0") int offset,
                                          @RequestParam(defaultValue = "10000") int length) throws IOException {
        return ResponseEntity.ok(service.readFileText(id, offset, length));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable String id) {
        File f = service.originalFileFor(id);
        if (f == null) return ResponseEntity.notFound().build();
        String encoded = URLEncoder.encode(f.getName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(f));
    }

    // ---------- HTTPS 专用 ----------

    @PostMapping("/{id}/execute")
    public ResponseEntity<HttpExecuteResponse> executeHttp(@PathVariable String id) {
        return ResponseEntity.ok(service.executeHttp(id));
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<List<DataSourceFetchLogPO>> logs(@PathVariable String id) {
        return ResponseEntity.ok(service.listLogs(id));
    }

    @PutMapping("/{id}/schedule")
    public ResponseEntity<SuccessCountResponse> schedule(@PathVariable String id,
                                                         @RequestBody HttpScheduleRequest req) {
        service.schedule(id, req);
        return ResponseEntity.ok(new SuccessCountResponse(true, 1));
    }
}
```

- [ ] **Step 2：启动后端验证端点存在**

```bash
cd backend && mvn -q -o spring-boot:run
```

另开终端：

```bash
curl -s -H "X-Workspace-Id: ws_default" http://localhost:8000/api/data-sources
```

Expected：返回 JSON 数组（可能为空 `[]`）。

- [ ] **Step 3：手测 HTTPS 创建 + 执行**

```bash
curl -s -X POST -H "Content-Type: application/json" -H "X-Workspace-Id: ws_default" \
  -d '{"name":"httpbin GET","kind":"https_api","config":{"url":"https://httpbin.org/get","method":"GET","timeoutMs":15000,"headers":{}}}' \
  http://localhost:8000/api/data-sources
```

记下返回的 id（如 `ds_xxx`），再：

```bash
curl -s -X POST -H "X-Workspace-Id: ws_default" \
  http://localhost:8000/api/data-sources/<id>/execute
```

Expected：返回 `{"success":true,"statusCode":200,...}`。

- [ ] **Step 4：手测 SSRF 拦截**

```bash
curl -s -X POST -H "Content-Type: application/json" -H "X-Workspace-Id: ws_default" \
  -d '{"name":"local","kind":"https_api","config":{"url":"http://localhost:8000/api/data-sources","method":"GET"}}' \
  http://localhost:8000/api/data-sources
# 创建会成功（仅校验格式），但 execute 时被拒：
curl -s -X POST -H "X-Workspace-Id: ws_default" \
  http://localhost:8000/api/data-sources/<id>/execute
```

Expected：响应中 `success=false`、`errorMsg` 含"禁止访问内网"。

- [ ] **Step 5：提交**

```bash
git add backend/src/main/java/com/tuiyan/backend/controller/DataSourceController.java
git commit -m "feat(ds): DataSourceController 暴露完整端点"
```

---

## Task 15：前端 API 客户端扩展

**Files:**
- Modify: `frontend/src/api/dataSources.ts`

- [ ] **Step 1：替换文件内容**

```ts
import { request } from './http';

export type DataSourceKind =
  | 'file' | 'url'                // 旧的导入历史
  | 'mysql' | 'pgsql' | 'file_stored' | 'https_api';

export interface DataSource {
  id: string;
  kind: DataSourceKind;
  name: string;
  mime?: string;
  size?: number;
  createdAt: number;
  status?: 'idle' | 'connected' | 'error';
  lastTestedAt?: number;
  lastError?: string;
  updatedAt?: number;
  /** 列表接口里返回的是 maskSensitive 后的 config；编辑表单请用 detail 端点 */
  config?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface DataSourceTestResult {
  success: boolean;
  message?: string;
  latencyMs?: number | null;
}

export interface TablePreview {
  columns: string[];
  rows: unknown[][];
  rowCount: number;
  truncated: boolean;
}

export interface SqlExecuteResult extends TablePreview {
  durationMs: number;
}

export interface HttpExecuteResult {
  success: boolean;
  statusCode?: number;
  headers?: Record<string, string>;
  body?: string;
  errorMsg?: string;
  durationMs: number;
  truncated: boolean;
}

export interface FetchLog {
  id: number;
  dataSourceId: string;
  fetchedAt: number;
  statusCode?: number;
  success: boolean;
  responseBody?: string;
  errorMsg?: string;
  durationMs: number;
}

export function listDataSources(opts?: { workspaceId?: string; kind?: DataSourceKind }) {
  const qs = new URLSearchParams();
  if (opts?.workspaceId) qs.set('workspaceId', opts.workspaceId);
  if (opts?.kind) qs.set('kind', opts.kind);
  const tail = qs.toString() ? `?${qs}` : '';
  return request<DataSource[]>(`/api/data-sources${tail}`);
}

export function getDataSource(id: string) {
  return request<DataSource>(`/api/data-sources/${encodeURIComponent(id)}`);
}

export function createDataSource(payload: { name: string; kind: DataSourceKind; config: Record<string, unknown> }) {
  return request<DataSource>('/api/data-sources', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export function updateDataSource(id: string, payload: { name?: string; config?: Record<string, unknown> }) {
  return request<DataSource>(`/api/data-sources/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export function deleteDataSource(id: string) {
  return request<{ success: boolean }>(`/api/data-sources/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

export function testDataSource(id: string) {
  return request<DataSourceTestResult>(`/api/data-sources/${encodeURIComponent(id)}/test`, {
    method: 'POST',
  });
}

export function testDataSourceInline(payload: { kind: DataSourceKind; config: Record<string, unknown> }) {
  return request<DataSourceTestResult>('/api/data-sources/test-inline', {
    method: 'POST',
    body: JSON.stringify({ name: '__inline__', ...payload }),
  });
}

// ---------- 数据库专用 ----------

export function listTables(id: string) {
  return request<string[]>(`/api/data-sources/${encodeURIComponent(id)}/tables`);
}

export function previewTable(id: string, name: string, limit = 50) {
  return request<TablePreview>(
    `/api/data-sources/${encodeURIComponent(id)}/tables/${encodeURIComponent(name)}/preview?limit=${limit}`,
  );
}

export function executeSql(id: string, sql: string, limit = 100) {
  return request<SqlExecuteResult>(`/api/data-sources/${encodeURIComponent(id)}/sql`, {
    method: 'POST',
    body: JSON.stringify({ sql, limit }),
  });
}

// ---------- 文件专用 ----------

export function uploadFileDataSource(file: File, name?: string) {
  const form = new FormData();
  form.append('file', file);
  if (name) form.append('name', name);
  return request<DataSource>('/api/data-sources/file', {
    method: 'POST',
    body: form,
  });
}

export function readFileContent(id: string, offset = 0, length = 10000) {
  const qs = `?offset=${offset}&length=${length}`;
  return request<string>(`/api/data-sources/${encodeURIComponent(id)}/content${qs}`);
}

export function fileDownloadUrl(id: string) {
  return `/api/data-sources/${encodeURIComponent(id)}/download`;
}

// ---------- HTTPS 专用 ----------

export function executeHttp(id: string) {
  return request<HttpExecuteResult>(`/api/data-sources/${encodeURIComponent(id)}/execute`, {
    method: 'POST',
  });
}

export function listFetchLogs(id: string) {
  return request<FetchLog[]>(`/api/data-sources/${encodeURIComponent(id)}/logs`);
}

export function updateSchedule(id: string, enabled: boolean, intervalSec?: number) {
  return request<{ success: boolean }>(`/api/data-sources/${encodeURIComponent(id)}/schedule`, {
    method: 'PUT',
    body: JSON.stringify({ enabled, intervalSec }),
  });
}
```

- [ ] **Step 2：类型检查**

```bash
cd frontend && npm run lint
```

Expected：通过（仅 ts 类型检查）。

- [ ] **Step 3：提交**

```bash
git add frontend/src/api/dataSources.ts
git commit -m "feat(ds): 前端 API 客户端覆盖全部数据源端点"
```

---

## Task 16：`DataSourceConfigForm.vue` —— 各 kind 表单（创建/编辑复用）

**Files:**
- Create: `frontend/src/components/datasource/DataSourceConfigForm.vue`

- [ ] **Step 1：创建表单组件**

```vue
<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { DataSourceKind } from '../../api/dataSources';

const props = defineProps<{
  kind: DataSourceKind;
  modelValue: Record<string, any>;
  nameValue: string;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', v: Record<string, any>): void;
  (e: 'update:nameValue', v: string): void;
}>();

const cfg = ref<Record<string, any>>({ ...props.modelValue });
const name = ref<string>(props.nameValue || '');

watch(() => props.modelValue, (v) => { cfg.value = { ...v }; }, { deep: true });
watch(() => props.nameValue, (v) => { name.value = v || ''; });

const emitConfig = () => emit('update:modelValue', { ...cfg.value });
const emitName = () => emit('update:nameValue', name.value);

// headers 走 [{key,value}] 列表方便编辑
const headerList = ref<{ k: string; v: string }[]>(
  Object.entries(cfg.value.headers || {}).map(([k, v]) => ({ k, v: String(v) })),
);
const syncHeaders = () => {
  const out: Record<string, string> = {};
  for (const it of headerList.value) {
    if (it.k.trim()) out[it.k.trim()] = it.v;
  }
  cfg.value.headers = out;
  emitConfig();
};
const addHeader = () => { headerList.value.push({ k: '', v: '' }); };
const removeHeader = (i: number) => { headerList.value.splice(i, 1); syncHeaders(); };

const scheduleEnabled = computed({
  get: () => !!cfg.value.schedule?.enabled,
  set: (v: boolean) => {
    cfg.value.schedule = { ...(cfg.value.schedule || {}), enabled: v };
    emitConfig();
  },
});
const scheduleInterval = computed({
  get: () => cfg.value.schedule?.intervalSec || 300,
  set: (v: number) => {
    cfg.value.schedule = { ...(cfg.value.schedule || {}), intervalSec: Number(v) || 300 };
    emitConfig();
  },
});
</script>

<template>
  <div class="ds-form">
    <label class="row">
      <span>名称</span>
      <input v-model="name" @input="emitName" placeholder="数据源名称" />
    </label>

    <template v-if="kind === 'mysql' || kind === 'pgsql'">
      <label class="row"><span>Host</span><input v-model="cfg.host" @input="emitConfig" placeholder="localhost" /></label>
      <label class="row"><span>Port</span><input v-model.number="cfg.port" @input="emitConfig" :placeholder="kind === 'mysql' ? '3306' : '5432'" /></label>
      <label class="row"><span>Database</span><input v-model="cfg.database" @input="emitConfig" /></label>
      <label class="row"><span>Username</span><input v-model="cfg.username" @input="emitConfig" /></label>
      <label class="row"><span>Password</span><input type="password" v-model="cfg.password" @input="emitConfig" /></label>
      <label class="row"><span>额外参数</span><input v-model="cfg.params" @input="emitConfig" placeholder="如 useSSL=false&serverTimezone=UTC" /></label>
    </template>

    <template v-if="kind === 'https_api'">
      <label class="row"><span>URL</span><input v-model="cfg.url" @input="emitConfig" placeholder="https://api.example.com/..." /></label>
      <label class="row">
        <span>方法</span>
        <select v-model="cfg.method" @change="emitConfig">
          <option>GET</option><option>POST</option><option>PUT</option><option>DELETE</option>
        </select>
      </label>
      <div class="row block">
        <span>Headers</span>
        <div class="kv-list">
          <div v-for="(h, i) in headerList" :key="i" class="kv">
            <input v-model="h.k" @input="syncHeaders" placeholder="Header" />
            <input v-model="h.v" @input="syncHeaders" placeholder="Value" />
            <button type="button" @click="removeHeader(i)">×</button>
          </div>
          <button type="button" class="add" @click="addHeader">+ 添加 Header</button>
        </div>
      </div>
      <label v-if="cfg.method === 'POST' || cfg.method === 'PUT'" class="row block">
        <span>Body</span>
        <textarea v-model="cfg.body" @input="emitConfig" rows="4" placeholder='{"key":"value"}'></textarea>
      </label>
      <label class="row"><span>超时(ms)</span><input v-model.number="cfg.timeoutMs" @input="emitConfig" placeholder="15000" /></label>
      <div class="row schedule">
        <label class="schedule-toggle">
          <input type="checkbox" v-model="scheduleEnabled" />
          <span>定时拉取</span>
        </label>
        <input v-if="scheduleEnabled" type="number" v-model.number="scheduleInterval" min="60" placeholder="秒，≥60" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.ds-form { display: flex; flex-direction: column; gap: 10px; }
.row { display: flex; align-items: center; gap: 8px; }
.row > span { min-width: 88px; font-size: 13px; color: #c0c4cf; }
.row > input, .row > select, .row > textarea {
  flex: 1; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12);
  border-radius: 6px; padding: 6px 8px; color: #e8eaed; font-size: 13px;
}
.row.block { flex-direction: column; align-items: stretch; }
.row.block > span { margin-bottom: 4px; }
.kv-list { display: flex; flex-direction: column; gap: 6px; }
.kv { display: flex; gap: 6px; }
.kv input { flex: 1; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 6px 8px; color: #e8eaed; }
.kv button, .add { background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 4px 10px; color: #c0c4cf; cursor: pointer; }
.schedule { gap: 12px; }
.schedule-toggle { display: flex; align-items: center; gap: 6px; color: #c0c4cf; }
</style>
```

- [ ] **Step 2：lint + 提交**

```bash
cd frontend && npm run lint
git add frontend/src/components/datasource/DataSourceConfigForm.vue
git commit -m "feat(ds): DataSourceConfigForm 各 kind 表单"
```

---

## Task 17：`DataSourceCreateDialog.vue` —— 创建对话框（含文件上传分支）

**Files:**
- Create: `frontend/src/components/DataSourceCreateDialog.vue`

- [ ] **Step 1：创建对话框**

```vue
<script setup lang="ts">
import { ref } from 'vue';
import { createDataSource, testDataSourceInline, uploadFileDataSource } from '../api/dataSources';
import type { DataSourceKind } from '../api/dataSources';
import { ApiError } from '../api/http';
import { toast } from '../composables/useToast';
import DataSourceConfigForm from './datasource/DataSourceConfigForm.vue';

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'created', id: string): void;
}>();

const step = ref<'pick' | 'form'>('pick');
const kind = ref<DataSourceKind | null>(null);
const name = ref('');
const cfg = ref<Record<string, any>>({});
const fileToUpload = ref<File | null>(null);
const submitting = ref(false);
const testing = ref(false);
const testMsg = ref<string>('');

const TYPES: { kind: DataSourceKind; icon: string; label: string; desc: string }[] = [
  { kind: 'mysql',       icon: '🗄', label: 'MySQL',       desc: '连接 MySQL 数据库，查表写 SQL' },
  { kind: 'pgsql',       icon: '🐘', label: 'PostgreSQL',  desc: '连接 PgSQL 数据库，查表写 SQL' },
  { kind: 'file_stored', icon: '📄', label: '文件',         desc: '上传 PDF / TXT / MD' },
  { kind: 'https_api',   icon: '🌐', label: 'HTTPS 接口',  desc: 'REST API，可定时拉取' },
];

const pickType = (k: DataSourceKind) => {
  kind.value = k;
  step.value = 'form';
  if (k === 'mysql') cfg.value = { host: 'localhost', port: 3306, database: '', username: '', password: '', params: '' };
  if (k === 'pgsql') cfg.value = { host: 'localhost', port: 5432, database: '', username: '', password: '', params: '' };
  if (k === 'https_api') cfg.value = { url: '', method: 'GET', headers: {}, body: '', timeoutMs: 15000, schedule: { enabled: false, intervalSec: 300 } };
  if (k === 'file_stored') cfg.value = {};
};

const onFilePick = (ev: Event) => {
  const t = ev.target as HTMLInputElement;
  const f = t.files?.[0];
  if (f) {
    fileToUpload.value = f;
    if (!name.value) name.value = f.name;
  }
};

const runTest = async () => {
  if (!kind.value || kind.value === 'file_stored') return;
  testing.value = true;
  testMsg.value = '';
  try {
    const r = await testDataSourceInline({ kind: kind.value, config: cfg.value });
    testMsg.value = r.success
      ? `连接成功 (${r.latencyMs ?? '-'}ms)`
      : `连接失败：${r.message || '未知错误'}`;
  } catch (e) {
    testMsg.value = `异常：${(e as Error).message}`;
  } finally {
    testing.value = false;
  }
};

const submit = async () => {
  if (!kind.value) return;
  if (!name.value.trim()) { toast('请填写名称'); return; }
  submitting.value = true;
  try {
    let created;
    if (kind.value === 'file_stored') {
      if (!fileToUpload.value) { toast('请选择文件'); submitting.value = false; return; }
      created = await uploadFileDataSource(fileToUpload.value, name.value.trim());
    } else {
      created = await createDataSource({ name: name.value.trim(), kind: kind.value, config: cfg.value });
    }
    toast('已创建');
    emit('created', created.id);
    emit('close');
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : (e as Error).message;
    toast(`创建失败：${msg}`);
  } finally {
    submitting.value = false;
  }
};
</script>

<template>
  <div class="dlg-mask" @click.self="emit('close')">
    <div class="dlg">
      <div class="dlg-head">
        <h3>添加数据源</h3>
        <button @click="emit('close')">×</button>
      </div>
      <div class="dlg-body">
        <div v-if="step === 'pick'" class="picker">
          <button v-for="t in TYPES" :key="t.kind" class="type-card" @click="pickType(t.kind)">
            <span class="ic">{{ t.icon }}</span>
            <strong>{{ t.label }}</strong>
            <small>{{ t.desc }}</small>
          </button>
        </div>
        <div v-else class="form">
          <DataSourceConfigForm
            v-if="kind && kind !== 'file_stored'"
            :kind="kind"
            v-model="cfg"
            v-model:name-value="name"
          />
          <template v-else>
            <label class="row">
              <span>名称</span>
              <input v-model="name" placeholder="数据源名称" />
            </label>
            <label class="row block">
              <span>文件 (PDF / TXT / MD)</span>
              <input type="file" accept=".pdf,.txt,.md" @change="onFilePick" />
              <small v-if="fileToUpload">{{ fileToUpload.name }} ({{ (fileToUpload.size / 1024).toFixed(1) }} KB)</small>
            </label>
          </template>
          <div v-if="testMsg" class="test-msg">{{ testMsg }}</div>
        </div>
      </div>
      <div class="dlg-foot">
        <button v-if="step === 'form'" class="ghost" @click="step = 'pick'">‹ 返回</button>
        <span class="spacer" />
        <button
          v-if="step === 'form' && kind && kind !== 'file_stored'"
          class="ghost" :disabled="testing" @click="runTest"
        >{{ testing ? '测试中…' : '测试连接' }}</button>
        <button class="primary" :disabled="step === 'pick' || submitting" @click="submit">
          {{ submitting ? '提交中…' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dlg-mask { position: fixed; inset: 0; background: rgba(0,0,0,.5); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.dlg { width: 560px; max-height: 80vh; background: #1d1f24; color: #e8eaed; border-radius: 10px; display: flex; flex-direction: column; border: 1px solid rgba(255,255,255,.08); }
.dlg-head, .dlg-foot { display: flex; align-items: center; padding: 12px 16px; }
.dlg-head { border-bottom: 1px solid rgba(255,255,255,.06); }
.dlg-head h3 { margin: 0; font-size: 15px; flex: 1; }
.dlg-head button { background: none; border: none; color: #aaa; font-size: 18px; cursor: pointer; }
.dlg-body { padding: 16px; overflow-y: auto; flex: 1; }
.dlg-foot { border-top: 1px solid rgba(255,255,255,.06); gap: 8px; }
.spacer { flex: 1; }
.picker { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.type-card { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.08); border-radius: 8px; padding: 12px; cursor: pointer; color: #e8eaed; }
.type-card:hover { background: rgba(255,255,255,.08); }
.type-card .ic { font-size: 20px; }
.type-card strong { font-size: 14px; }
.type-card small { font-size: 12px; color: #aaa; }
.form { display: flex; flex-direction: column; gap: 10px; }
.row { display: flex; align-items: center; gap: 8px; }
.row > span { min-width: 88px; font-size: 13px; color: #c0c4cf; }
.row > input { flex: 1; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 6px 8px; color: #e8eaed; }
.row.block { flex-direction: column; align-items: stretch; gap: 6px; }
.test-msg { font-size: 13px; color: #aaa; padding: 8px; background: rgba(255,255,255,.03); border-radius: 6px; }
.primary { background: #4a8df0; border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; }
.primary:disabled { opacity: .5; cursor: not-allowed; }
.ghost { background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 6px 12px; color: #e8eaed; cursor: pointer; }
</style>
```

- [ ] **Step 2：lint + 提交**

```bash
cd frontend && npm run lint
git add frontend/src/components/DataSourceCreateDialog.vue
git commit -m "feat(ds): DataSourceCreateDialog 创建对话框"
```

---

## Task 18：详情视图的子 tab 组件 —— 数据库

**Files:**
- Create: `frontend/src/components/datasource/DbOverviewTab.vue`
- Create: `frontend/src/components/datasource/DbTableListTab.vue`
- Create: `frontend/src/components/datasource/DbSqlTab.vue`

- [ ] **Step 1：`DbOverviewTab.vue`**

```vue
<script setup lang="ts">
import { ref } from 'vue';
import type { DataSource } from '../../api/dataSources';
import { testDataSource } from '../../api/dataSources';
import { toast } from '../../composables/useToast';

const props = defineProps<{ ds: DataSource }>();
const emit = defineEmits<{ (e: 'updated'): void }>();
const testing = ref(false);

const fmtTime = (t?: number) => t ? new Date(t).toLocaleString() : '—';

const runTest = async () => {
  testing.value = true;
  try {
    const r = await testDataSource(props.ds.id);
    toast(r.success ? `连接成功 (${r.latencyMs ?? '-'}ms)` : `连接失败：${r.message}`);
    emit('updated');
  } finally { testing.value = false; }
};
</script>

<template>
  <div class="overview">
    <dl>
      <dt>类型</dt><dd>{{ ds.kind === 'mysql' ? 'MySQL' : 'PostgreSQL' }}</dd>
      <dt>Host</dt><dd>{{ (ds.config as any)?.host }}:{{ (ds.config as any)?.port }}</dd>
      <dt>Database</dt><dd>{{ (ds.config as any)?.database }}</dd>
      <dt>Username</dt><dd>{{ (ds.config as any)?.username }}</dd>
      <dt>状态</dt><dd>
        <span :class="['status', ds.status]">{{ ds.status }}</span>
      </dd>
      <dt>最后测试</dt><dd>{{ fmtTime(ds.lastTestedAt) }}</dd>
      <dt v-if="ds.lastError">最后错误</dt>
      <dd v-if="ds.lastError" class="err">{{ ds.lastError }}</dd>
    </dl>
    <button class="primary" :disabled="testing" @click="runTest">
      {{ testing ? '测试中…' : '测试连接' }}
    </button>
  </div>
</template>

<style scoped>
.overview { padding: 16px; color: #e8eaed; }
dl { display: grid; grid-template-columns: 110px 1fr; gap: 6px 12px; margin-bottom: 12px; }
dt { color: #888; font-size: 13px; }
dd { color: #e8eaed; font-size: 13px; margin: 0; }
.status { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.status.connected { background: rgba(34,221,136,.2); color: #22dd88; }
.status.error { background: rgba(255,99,71,.2); color: tomato; }
.status.idle { background: rgba(255,255,255,.1); color: #aaa; }
.err { color: tomato; font-size: 12px; }
.primary { background: #4a8df0; border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; }
.primary:disabled { opacity: .5; }
</style>
```

- [ ] **Step 2：`DbTableListTab.vue`**

```vue
<script setup lang="ts">
import { ref, watch, onMounted } from 'vue';
import { listTables, previewTable } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import type { TablePreview } from '../../api/dataSources';

const props = defineProps<{ dsId: string }>();
const tables = ref<string[]>([]);
const loading = ref(false);
const selected = ref<string | null>(null);
const preview = ref<TablePreview | null>(null);
const previewing = ref(false);
const err = ref<string>('');

const load = async () => {
  loading.value = true;
  err.value = '';
  try { tables.value = await listTables(props.dsId); }
  catch (e) { err.value = e instanceof ApiError ? e.message : (e as Error).message; }
  finally { loading.value = false; }
};
const pick = async (name: string) => {
  selected.value = name;
  preview.value = null;
  previewing.value = true;
  err.value = '';
  try { preview.value = await previewTable(props.dsId, name, 50); }
  catch (e) { err.value = e instanceof ApiError ? e.message : (e as Error).message; }
  finally { previewing.value = false; }
};

onMounted(load);
watch(() => props.dsId, () => { tables.value = []; selected.value = null; preview.value = null; load(); });
</script>

<template>
  <div class="tab">
    <aside class="tlist">
      <div class="head">
        <span>表 ({{ tables.length }})</span>
        <button @click="load">↻</button>
      </div>
      <div v-if="loading" class="msg">加载中…</div>
      <div v-else-if="err" class="msg err">{{ err }}</div>
      <ul v-else>
        <li v-for="t in tables" :key="t" :class="{ active: t === selected }" @click="pick(t)">{{ t }}</li>
      </ul>
    </aside>
    <main class="ptable">
      <div v-if="!selected" class="hint">从左侧选择一张表预览前 50 行</div>
      <div v-else-if="previewing" class="msg">查询中…</div>
      <div v-else-if="preview">
        <div class="meta">{{ selected }}：{{ preview.rowCount }} 行{{ preview.truncated ? '（已截断）' : '' }}</div>
        <div class="scroll">
          <table>
            <thead><tr><th v-for="c in preview.columns" :key="c">{{ c }}</th></tr></thead>
            <tbody>
              <tr v-for="(row, i) in preview.rows" :key="i">
                <td v-for="(v, j) in row" :key="j">{{ v === null ? '∅' : String(v) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.tab { display: flex; height: 100%; min-height: 320px; }
.tlist { width: 220px; border-right: 1px solid rgba(255,255,255,.06); display: flex; flex-direction: column; }
.tlist .head { display: flex; align-items: center; padding: 8px 12px; color: #888; font-size: 12px; }
.tlist .head span { flex: 1; }
.tlist .head button { background: none; border: none; color: #aaa; cursor: pointer; }
.tlist ul { list-style: none; padding: 0; margin: 0; overflow-y: auto; flex: 1; }
.tlist li { padding: 6px 12px; cursor: pointer; color: #c0c4cf; font-size: 13px; }
.tlist li:hover { background: rgba(255,255,255,.06); }
.tlist li.active { background: rgba(74,141,240,.2); color: #fff; }
.ptable { flex: 1; padding: 12px; overflow: hidden; display: flex; flex-direction: column; }
.meta { color: #aaa; font-size: 12px; margin-bottom: 8px; }
.scroll { overflow: auto; flex: 1; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }
th, td { padding: 4px 8px; text-align: left; border-bottom: 1px solid rgba(255,255,255,.06); color: #e8eaed; white-space: nowrap; }
th { color: #aaa; position: sticky; top: 0; background: #1d1f24; }
.hint, .msg { color: #888; padding: 12px; font-size: 13px; }
.msg.err { color: tomato; }
</style>
```

- [ ] **Step 3：`DbSqlTab.vue`**

```vue
<script setup lang="ts">
import { ref } from 'vue';
import { executeSql } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import type { SqlExecuteResult } from '../../api/dataSources';

const props = defineProps<{ dsId: string }>();
const sql = ref('SELECT 1');
const limit = ref(100);
const result = ref<SqlExecuteResult | null>(null);
const running = ref(false);
const err = ref<string>('');

const run = async () => {
  running.value = true;
  err.value = '';
  result.value = null;
  try { result.value = await executeSql(props.dsId, sql.value, limit.value); }
  catch (e) { err.value = e instanceof ApiError ? e.message : (e as Error).message; }
  finally { running.value = false; }
};
</script>

<template>
  <div class="tab">
    <div class="toolbar">
      <textarea v-model="sql" rows="4" placeholder="仅允许 SELECT / SHOW / DESC / EXPLAIN" />
      <div class="row">
        <label>LIMIT <input type="number" v-model.number="limit" min="1" max="1000" /></label>
        <button class="primary" :disabled="running" @click="run">{{ running ? '执行中…' : '执行' }}</button>
      </div>
    </div>
    <div class="output">
      <div v-if="err" class="msg err">{{ err }}</div>
      <div v-else-if="result">
        <div class="meta">{{ result.rowCount }} 行 · {{ result.durationMs }}ms{{ result.truncated ? ' · 已截断' : '' }}</div>
        <div class="scroll">
          <table>
            <thead><tr><th v-for="c in result.columns" :key="c">{{ c }}</th></tr></thead>
            <tbody>
              <tr v-for="(row, i) in result.rows" :key="i">
                <td v-for="(v, j) in row" :key="j">{{ v === null ? '∅' : String(v) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tab { padding: 12px; display: flex; flex-direction: column; gap: 10px; height: 100%; min-height: 320px; }
.toolbar { display: flex; flex-direction: column; gap: 8px; }
textarea { background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 8px; color: #e8eaed; font-family: monospace; font-size: 13px; }
.row { display: flex; align-items: center; gap: 12px; }
.row label { color: #aaa; font-size: 12px; }
.row input { width: 80px; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 4px 8px; color: #e8eaed; }
.primary { background: #4a8df0; border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; margin-left: auto; }
.primary:disabled { opacity: .5; }
.output { flex: 1; overflow: hidden; display: flex; flex-direction: column; }
.scroll { overflow: auto; flex: 1; }
.meta { color: #aaa; font-size: 12px; margin-bottom: 8px; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }
th, td { padding: 4px 8px; text-align: left; border-bottom: 1px solid rgba(255,255,255,.06); color: #e8eaed; white-space: nowrap; }
th { color: #aaa; position: sticky; top: 0; background: #1d1f24; }
.msg.err { color: tomato; padding: 12px; }
</style>
```

- [ ] **Step 4：lint + 提交**

```bash
cd frontend && npm run lint
git add frontend/src/components/datasource/Db*.vue
git commit -m "feat(ds): 数据库 tab 组件（概览/表列表/SQL）"
```

---

## Task 19：详情视图的子 tab 组件 —— 文件 + HTTPS

**Files:**
- Create: `frontend/src/components/datasource/FileContentTab.vue`
- Create: `frontend/src/components/datasource/HttpExecuteTab.vue`
- Create: `frontend/src/components/datasource/HttpHistoryTab.vue`
- Create: `frontend/src/components/datasource/HttpScheduleTab.vue`

- [ ] **Step 1：`FileContentTab.vue`**

```vue
<script setup lang="ts">
import { ref, watch, onMounted } from 'vue';
import { readFileContent, fileDownloadUrl } from '../../api/dataSources';

const props = defineProps<{ dsId: string; totalChars: number }>();
const offset = ref(0);
const PAGE = 10000;
const text = ref('');
const loading = ref(false);

const load = async () => {
  loading.value = true;
  try { text.value = await readFileContent(props.dsId, offset.value, PAGE); }
  finally { loading.value = false; }
};
const next = () => { if (offset.value + PAGE < props.totalChars) { offset.value += PAGE; load(); } };
const prev = () => { if (offset.value >= PAGE) { offset.value -= PAGE; load(); } };

onMounted(load);
watch(() => props.dsId, () => { offset.value = 0; load(); });
</script>

<template>
  <div class="tab">
    <div class="bar">
      <span>{{ offset }} – {{ Math.min(offset + PAGE, totalChars) }} / {{ totalChars }} 字符</span>
      <button :disabled="offset === 0" @click="prev">‹ 上一页</button>
      <button :disabled="offset + PAGE >= totalChars" @click="next">下一页 ›</button>
      <a :href="fileDownloadUrl(dsId)" target="_blank">下载原文件</a>
    </div>
    <pre v-if="!loading" class="content">{{ text }}</pre>
    <div v-else class="msg">加载中…</div>
  </div>
</template>

<style scoped>
.tab { padding: 12px; display: flex; flex-direction: column; gap: 8px; height: 100%; min-height: 320px; }
.bar { display: flex; align-items: center; gap: 12px; color: #aaa; font-size: 12px; }
.bar button, .bar a { background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 4px 10px; color: #c0c4cf; cursor: pointer; text-decoration: none; font-size: 12px; }
.bar button:disabled { opacity: .4; cursor: not-allowed; }
.content { flex: 1; overflow: auto; background: rgba(255,255,255,.03); padding: 12px; border-radius: 6px; color: #e8eaed; font-family: monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all; }
.msg { color: #888; padding: 12px; }
</style>
```

- [ ] **Step 2：`HttpExecuteTab.vue`**

```vue
<script setup lang="ts">
import { ref } from 'vue';
import { executeHttp } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import type { DataSource, HttpExecuteResult } from '../../api/dataSources';

const props = defineProps<{ ds: DataSource }>();
const result = ref<HttpExecuteResult | null>(null);
const running = ref(false);
const err = ref<string>('');

const run = async () => {
  running.value = true;
  err.value = '';
  result.value = null;
  try { result.value = await executeHttp(props.ds.id); }
  catch (e) { err.value = e instanceof ApiError ? e.message : (e as Error).message; }
  finally { running.value = false; }
};

const prettyBody = (s?: string) => {
  if (!s) return '';
  try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s; }
};
</script>

<template>
  <div class="tab">
    <div class="left">
      <h4>请求</h4>
      <dl>
        <dt>方法</dt><dd>{{ (ds.config as any)?.method || 'GET' }}</dd>
        <dt>URL</dt><dd class="url">{{ (ds.config as any)?.url }}</dd>
        <dt v-if="(ds.config as any)?.headers && Object.keys((ds.config as any).headers).length">Headers</dt>
        <dd v-if="(ds.config as any)?.headers && Object.keys((ds.config as any).headers).length">
          <div v-for="(v, k) in (ds.config as any).headers" :key="k"><b>{{ k }}:</b> {{ v }}</div>
        </dd>
        <dt v-if="(ds.config as any)?.body">Body</dt>
        <dd v-if="(ds.config as any)?.body"><pre>{{ (ds.config as any).body }}</pre></dd>
      </dl>
      <button class="primary" :disabled="running" @click="run">{{ running ? '执行中…' : '立即执行' }}</button>
    </div>
    <div class="right">
      <h4>响应</h4>
      <div v-if="err" class="msg err">{{ err }}</div>
      <div v-else-if="!result" class="msg">点击「立即执行」</div>
      <div v-else>
        <div class="status">
          <span :class="['code', result.success ? 'ok' : 'bad']">{{ result.statusCode ?? '—' }}</span>
          <span class="latency">{{ result.durationMs }}ms</span>
          <span v-if="result.truncated" class="trunc">已截断</span>
        </div>
        <div v-if="result.errorMsg" class="errbox">{{ result.errorMsg }}</div>
        <pre v-if="result.body" class="body">{{ prettyBody(result.body) }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tab { display: flex; gap: 12px; padding: 12px; height: 100%; min-height: 320px; }
.left, .right { flex: 1; display: flex; flex-direction: column; min-width: 0; }
h4 { margin: 0 0 8px; color: #aaa; font-size: 13px; }
dl { display: grid; grid-template-columns: 80px 1fr; gap: 4px 12px; font-size: 12px; margin-bottom: 12px; }
dt { color: #888; }
dd { color: #e8eaed; margin: 0; word-break: break-all; }
dd.url { font-family: monospace; }
dd pre { margin: 0; font-size: 12px; background: rgba(255,255,255,.03); padding: 6px; border-radius: 4px; }
.primary { background: #4a8df0; border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; align-self: flex-start; }
.primary:disabled { opacity: .5; }
.status { display: flex; gap: 12px; align-items: center; margin-bottom: 8px; font-size: 12px; }
.code { padding: 2px 8px; border-radius: 4px; font-weight: 600; }
.code.ok { background: rgba(34,221,136,.2); color: #22dd88; }
.code.bad { background: rgba(255,99,71,.2); color: tomato; }
.latency { color: #aaa; }
.trunc { color: #ffaa22; }
.errbox { color: tomato; background: rgba(255,99,71,.1); padding: 6px; border-radius: 4px; font-size: 12px; margin-bottom: 8px; }
.body { flex: 1; overflow: auto; background: rgba(255,255,255,.03); padding: 8px; border-radius: 6px; color: #e8eaed; font-family: monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all; }
.msg { color: #888; padding: 8px; }
.msg.err { color: tomato; }
</style>
```

- [ ] **Step 3：`HttpHistoryTab.vue`**

```vue
<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { listFetchLogs } from '../../api/dataSources';
import type { FetchLog } from '../../api/dataSources';

const props = defineProps<{ dsId: string }>();
const logs = ref<FetchLog[]>([]);
const loading = ref(false);
const expandedId = ref<number | null>(null);

const load = async () => {
  loading.value = true;
  try { logs.value = await listFetchLogs(props.dsId); }
  finally { loading.value = false; }
};
const fmtTime = (t: number) => new Date(t).toLocaleString();

onMounted(load);
watch(() => props.dsId, load);
</script>

<template>
  <div class="tab">
    <div class="bar">
      <span>最近 {{ logs.length }} 条</span>
      <button @click="load">↻</button>
    </div>
    <div class="list">
      <div v-if="loading" class="msg">加载中…</div>
      <div v-else-if="!logs.length" class="msg">暂无记录</div>
      <div v-for="l in logs" :key="l.id" class="log">
        <div class="row" @click="expandedId = expandedId === l.id ? null : l.id">
          <span :class="['dot', l.success ? 'ok' : 'bad']" />
          <span class="code">{{ l.statusCode ?? '—' }}</span>
          <span class="time">{{ fmtTime(l.fetchedAt) }}</span>
          <span class="dur">{{ l.durationMs }}ms</span>
        </div>
        <div v-if="expandedId === l.id" class="body">
          <div v-if="l.errorMsg" class="err">{{ l.errorMsg }}</div>
          <pre v-if="l.responseBody">{{ l.responseBody }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tab { padding: 12px; height: 100%; min-height: 320px; display: flex; flex-direction: column; }
.bar { display: flex; align-items: center; gap: 8px; color: #aaa; font-size: 12px; margin-bottom: 8px; }
.bar button { background: none; border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 2px 8px; color: #c0c4cf; cursor: pointer; }
.list { flex: 1; overflow: auto; }
.log { border-bottom: 1px solid rgba(255,255,255,.06); }
.row { display: flex; gap: 12px; align-items: center; padding: 8px 4px; cursor: pointer; font-size: 12px; }
.row:hover { background: rgba(255,255,255,.04); }
.dot { width: 8px; height: 8px; border-radius: 50%; }
.dot.ok { background: #22dd88; }
.dot.bad { background: tomato; }
.code { color: #c0c4cf; font-weight: 600; min-width: 36px; }
.time { color: #888; flex: 1; }
.dur { color: #aaa; }
.body { padding: 8px; background: rgba(0,0,0,.2); }
.body pre { margin: 0; font-family: monospace; font-size: 12px; color: #e8eaed; white-space: pre-wrap; word-break: break-all; }
.err { color: tomato; font-size: 12px; margin-bottom: 6px; }
.msg { color: #888; padding: 12px; }
</style>
```

- [ ] **Step 4：`HttpScheduleTab.vue`**

```vue
<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import { updateSchedule } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import { toast } from '../../composables/useToast';
import type { DataSource } from '../../api/dataSources';

const props = defineProps<{ ds: DataSource }>();
const emit = defineEmits<{ (e: 'updated'): void }>();

const enabled = ref<boolean>(!!(props.ds.config as any)?.schedule?.enabled);
const intervalSec = ref<number>(((props.ds.config as any)?.schedule?.intervalSec) || 300);
const saving = ref(false);

watch(() => props.ds.id, () => {
  enabled.value = !!(props.ds.config as any)?.schedule?.enabled;
  intervalSec.value = ((props.ds.config as any)?.schedule?.intervalSec) || 300;
});

const nextRun = computed(() => enabled.value
  ? new Date(Date.now() + intervalSec.value * 1000).toLocaleTimeString()
  : '—');

const save = async () => {
  saving.value = true;
  try {
    await updateSchedule(props.ds.id, enabled.value, intervalSec.value);
    toast(enabled.value ? '定时任务已启用' : '定时任务已停止');
    emit('updated');
  } catch (e) {
    toast(`保存失败：${e instanceof ApiError ? e.message : (e as Error).message}`);
  } finally { saving.value = false; }
};
</script>

<template>
  <div class="tab">
    <label class="toggle">
      <input type="checkbox" v-model="enabled" />
      <span>启用定时拉取</span>
    </label>
    <label class="row">
      <span>间隔 (秒)</span>
      <input type="number" v-model.number="intervalSec" min="60" />
      <small class="hint">最小 60 秒</small>
    </label>
    <div class="row">
      <span>下次执行</span>
      <span>{{ nextRun }}</span>
    </div>
    <button class="primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
  </div>
</template>

<style scoped>
.tab { padding: 16px; display: flex; flex-direction: column; gap: 12px; color: #e8eaed; }
.toggle { display: flex; gap: 8px; align-items: center; }
.row { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.row > span:first-child { min-width: 88px; color: #c0c4cf; }
.row input[type=number] { background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 4px 8px; color: #e8eaed; width: 100px; }
.hint { color: #888; font-size: 12px; }
.primary { background: #4a8df0; border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; align-self: flex-start; }
.primary:disabled { opacity: .5; }
</style>
```

- [ ] **Step 5：lint + 提交**

```bash
cd frontend && npm run lint
git add frontend/src/components/datasource/FileContentTab.vue \
        frontend/src/components/datasource/Http*.vue
git commit -m "feat(ds): 文件 + HTTPS tab 组件"
```

---

## Task 20：`DataSourceDetailView.vue` —— 主容器

**Files:**
- Create: `frontend/src/components/datasource/DataSourceDetailView.vue`

- [ ] **Step 1：创建容器**

```vue
<script setup lang="ts">
import { ref, watch, computed, onMounted } from 'vue';
import { getDataSource, updateDataSource } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import { toast } from '../../composables/useToast';
import type { DataSource } from '../../api/dataSources';
import DbOverviewTab from './DbOverviewTab.vue';
import DbTableListTab from './DbTableListTab.vue';
import DbSqlTab from './DbSqlTab.vue';
import FileContentTab from './FileContentTab.vue';
import HttpExecuteTab from './HttpExecuteTab.vue';
import HttpHistoryTab from './HttpHistoryTab.vue';
import HttpScheduleTab from './HttpScheduleTab.vue';
import DataSourceConfigForm from './DataSourceConfigForm.vue';

const props = defineProps<{ dsId: string }>();
const ds = ref<DataSource | null>(null);
const loading = ref(false);
const tab = ref<string>('overview');

const editing = ref(false);
const editName = ref('');
const editCfg = ref<Record<string, any>>({});
const saving = ref(false);

const load = async () => {
  loading.value = true;
  try {
    ds.value = await getDataSource(props.dsId);
    editName.value = ds.value.name;
    editCfg.value = { ...(ds.value.config || {}) };
    // 默认进入"概览"或文件的"内容预览"
    tab.value = ds.value.kind === 'file_stored' ? 'content' : 'overview';
  } catch (e) {
    toast(`加载失败：${e instanceof ApiError ? e.message : (e as Error).message}`);
  } finally { loading.value = false; }
};

const tabs = computed(() => {
  if (!ds.value) return [];
  if (ds.value.kind === 'mysql' || ds.value.kind === 'pgsql') {
    return [
      { id: 'overview', label: 'ℹ 概览' },
      { id: 'tables',   label: '📋 表列表' },
      { id: 'sql',      label: '📝 SQL 查询' },
      { id: 'config',   label: '⚙ 配置' },
    ];
  }
  if (ds.value.kind === 'file_stored') {
    return [
      { id: 'overview', label: 'ℹ 概览' },
      { id: 'content',  label: '📖 内容预览' },
      { id: 'config',   label: '⚙ 配置' },
    ];
  }
  if (ds.value.kind === 'https_api') {
    return [
      { id: 'overview', label: 'ℹ 概览' },
      { id: 'execute',  label: '▶ 执行' },
      { id: 'history',  label: '📜 历史' },
      { id: 'schedule', label: '⏰ 定时' },
      { id: 'config',   label: '⚙ 配置' },
    ];
  }
  return [];
});

const saveEdit = async () => {
  saving.value = true;
  try {
    await updateDataSource(props.dsId, { name: editName.value, config: editCfg.value });
    toast('已保存');
    await load();
    editing.value = false;
  } catch (e) {
    toast(`保存失败：${e instanceof ApiError ? e.message : (e as Error).message}`);
  } finally { saving.value = false; }
};

onMounted(load);
watch(() => props.dsId, load);
</script>

<template>
  <div class="detail">
    <div v-if="loading" class="msg">加载中…</div>
    <div v-else-if="!ds" class="msg">数据源不存在</div>
    <template v-else>
      <header>
        <h2>{{ ds.name }}</h2>
        <span class="kind-tag">{{ ds.kind }}</span>
      </header>
      <nav class="tabs">
        <button v-for="t in tabs" :key="t.id" :class="{ active: tab === t.id }" @click="tab = t.id">{{ t.label }}</button>
      </nav>
      <section class="content">
        <DbOverviewTab v-if="tab === 'overview' && (ds.kind === 'mysql' || ds.kind === 'pgsql')" :ds="ds" @updated="load" />
        <DbTableListTab v-if="tab === 'tables'" :ds-id="ds.id" />
        <DbSqlTab v-if="tab === 'sql'" :ds-id="ds.id" />
        <FileContentTab v-if="tab === 'content' && ds.kind === 'file_stored'" :ds-id="ds.id" :total-chars="Number((ds.config as any)?.chars || 0)" />
        <div v-if="tab === 'overview' && ds.kind === 'file_stored'" class="overview">
          <dl>
            <dt>文件名</dt><dd>{{ (ds.config as any)?.originalName }}</dd>
            <dt>大小</dt><dd>{{ ((ds.config as any)?.sizeBytes / 1024).toFixed(1) }} KB</dd>
            <dt v-if="(ds.config as any)?.pages">页数</dt>
            <dd v-if="(ds.config as any)?.pages">{{ (ds.config as any).pages }}</dd>
            <dt>字符数</dt><dd>{{ (ds.config as any)?.chars }}</dd>
            <dt>上传时间</dt><dd>{{ new Date(ds.createdAt).toLocaleString() }}</dd>
          </dl>
        </div>
        <HttpExecuteTab v-if="tab === 'execute' && ds.kind === 'https_api'" :ds="ds" />
        <HttpHistoryTab v-if="tab === 'history' && ds.kind === 'https_api'" :ds-id="ds.id" />
        <HttpScheduleTab v-if="tab === 'schedule' && ds.kind === 'https_api'" :ds="ds" @updated="load" />
        <div v-if="tab === 'overview' && ds.kind === 'https_api'" class="overview">
          <dl>
            <dt>方法</dt><dd>{{ (ds.config as any)?.method }}</dd>
            <dt>URL</dt><dd>{{ (ds.config as any)?.url }}</dd>
            <dt>状态</dt><dd><span :class="['status', ds.status]">{{ ds.status }}</span></dd>
            <dt>最后测试</dt><dd>{{ ds.lastTestedAt ? new Date(ds.lastTestedAt).toLocaleString() : '—' }}</dd>
            <dt v-if="ds.lastError">最后错误</dt>
            <dd v-if="ds.lastError" class="err">{{ ds.lastError }}</dd>
          </dl>
        </div>
        <div v-if="tab === 'config'" class="config-pane">
          <DataSourceConfigForm
            v-if="ds.kind !== 'file_stored'"
            :kind="ds.kind"
            v-model="editCfg"
            v-model:name-value="editName"
          />
          <label v-else class="row">
            <span>名称</span>
            <input v-model="editName" />
          </label>
          <button class="primary" :disabled="saving" @click="saveEdit">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.detail { display: flex; flex-direction: column; height: 100%; color: #e8eaed; }
header { display: flex; align-items: center; gap: 12px; padding: 12px 16px; border-bottom: 1px solid rgba(255,255,255,.06); }
header h2 { margin: 0; font-size: 16px; }
.kind-tag { padding: 2px 8px; background: rgba(255,255,255,.06); border-radius: 4px; font-size: 11px; color: #aaa; }
.tabs { display: flex; gap: 4px; padding: 0 12px; border-bottom: 1px solid rgba(255,255,255,.06); }
.tabs button { background: none; border: none; padding: 8px 12px; color: #aaa; cursor: pointer; font-size: 13px; border-bottom: 2px solid transparent; }
.tabs button.active { color: #fff; border-bottom-color: #4a8df0; }
.content { flex: 1; overflow: hidden; display: flex; }
.content > * { flex: 1; }
.overview { padding: 16px; }
.overview dl { display: grid; grid-template-columns: 110px 1fr; gap: 6px 12px; }
.overview dt { color: #888; font-size: 13px; }
.overview dd { color: #e8eaed; font-size: 13px; margin: 0; }
.status { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.status.connected { background: rgba(34,221,136,.2); color: #22dd88; }
.status.error { background: rgba(255,99,71,.2); color: tomato; }
.status.idle { background: rgba(255,255,255,.1); color: #aaa; }
.err { color: tomato; }
.config-pane { padding: 16px; display: flex; flex-direction: column; gap: 12px; max-width: 520px; }
.row { display: flex; align-items: center; gap: 8px; }
.row > span { min-width: 88px; font-size: 13px; color: #c0c4cf; }
.row > input { flex: 1; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 6px 8px; color: #e8eaed; }
.primary { background: #4a8df0; border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; align-self: flex-start; }
.primary:disabled { opacity: .5; }
.msg { color: #888; padding: 24px; }
</style>
```

- [ ] **Step 2：lint + 提交**

```bash
cd frontend && npm run lint
git add frontend/src/components/datasource/DataSourceDetailView.vue
git commit -m "feat(ds): DataSourceDetailView 主容器"
```

---

## Task 21：`App.vue` 视图态扩展 + Sidebar 触发

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/components/Sidebar.vue`
- Modify: `frontend/src/components/WorkspaceNode.vue`

- [ ] **Step 1：`App.vue` 扩展视图态**

打开 `App.vue`，定位第 34 行：

```ts
const view = ref<'welcome' | 'list' | 'graph' | 'settings' | 'workspace-picker'>('workspace-picker');
```

改为：

```ts
const view = ref<'welcome' | 'list' | 'graph' | 'settings' | 'workspace-picker' | 'datasource'>('workspace-picker');
const currentDataSourceId = ref<string | null>(null);
```

在文件靠近底部、`<template>` 中找到现有的视图分发位置（搜索 `view === 'settings'` 这类条件），仿照添加一个分支：

```vue
<DataSourceDetailView v-else-if="view === 'datasource' && currentDataSourceId" :ds-id="currentDataSourceId" />
```

并在 `<script>` 顶部 imports 区追加：

```ts
import DataSourceDetailView from './components/datasource/DataSourceDetailView.vue';
```

在 Sidebar 的事件绑定区域追加（找到 `@open-conversation`、`@new-conversation` 的位置）：

```vue
@open-data-source="(id) => { currentDataSourceId = id; view = 'datasource'; }"
@open-create-data-source="dsCreateOpen = true"
```

并新增 dialog 状态与挂载（在 `<script>` 中 `compareDialogOpen` 附近添加）：

```ts
const dsCreateOpen = ref(false);
const onDsCreated = async (id: string) => {
  currentDataSourceId.value = id;
  view.value = 'datasource';
};
```

```vue
<DataSourceCreateDialog
  v-if="dsCreateOpen"
  @close="dsCreateOpen = false"
  @created="onDsCreated"
/>
```

并 import：

```ts
import DataSourceCreateDialog from './components/DataSourceCreateDialog.vue';
```

- [ ] **Step 2：`Sidebar.vue` 透传事件**

打开 `Sidebar.vue`，在 `defineEmits` 中追加：

```ts
(e: 'open-data-source', id: string): void;
(e: 'open-create-data-source'): void;
```

在 `WorkspaceNode` 的使用处（`<WorkspaceNode ...>`）追加：

```vue
@open-data-source="(id) => emit('open-data-source', id)"
@open-create-data-source="emit('open-create-data-source')"
```

- [ ] **Step 3：`WorkspaceNode.vue` 修改数据源条目交互**

读 `frontend/src/components/WorkspaceNode.vue` 找到数据源（`📂 数据源`）子区块。在 `defineEmits` 里追加：

```ts
(e: 'open-data-source', id: string): void;
(e: 'open-create-data-source'): void;
```

在数据源二级头的右端按钮区追加（与对话记录的 `+` 同位置）：

```vue
<button class="add" @click.stop="emit('open-create-data-source')" title="添加数据源">＋</button>
```

在数据源条目渲染（v-for）里把单击行为接出去，并按 kind 显示图标。先找到原本的 `<li class="ds-item">` 类似行（旧文件 hover 行为），在 `<span>{{ item.name }}</span>` 之前插入：

```vue
<span class="ds-icon" :title="item.kind">
  {{ ({ mysql:'🗄', pgsql:'🐘', file_stored:'📄', https_api:'🌐', file:'📎', url:'🔗' } as any)[item.kind] || '📁' }}
</span>
<span v-if="['mysql','pgsql','file_stored','https_api'].includes(String(item.kind))"
      :class="['status-dot', String(item.status||'idle')]"
      :title="item.lastError ? String(item.lastError) : String(item.status||'idle')" />
```

并把整行的点击改为：

```vue
<li
  class="ds-item"
  @click="if (['mysql','pgsql','file_stored','https_api'].includes(String(item.kind))) emit('open-data-source', String(item.id))"
>
```

在样式中追加：

```css
.status-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-left: 6px; vertical-align: middle; }
.status-dot.idle { background: #888; }
.status-dot.connected { background: #22dd88; }
.status-dot.error { background: tomato; }
.ds-icon { display: inline-block; min-width: 16px; }
```

> 如 WorkspaceNode 里实际类名与上述不同，按现有命名调整，但**不要**改变树形结构与 props 协议。

- [ ] **Step 4：启动前端 + 端到端手测**

```bash
cd frontend && npm run dev
```

浏览器打开 `http://localhost:5173`：

1. 在某工作空间下点击「📂 数据源」二级头的 `＋` → 弹出对话框
2. 选 MySQL，填本地数据库参数 → 「测试连接」显示成功 → 「保存」
3. 侧栏出现新条目，点击进入详情，「表列表」能看到表，「SQL 查询」执行 `SELECT 1` 正常
4. 重复用 PgSQL / 文件上传 / HTTPS 验证
5. HTTPS 启用定时拉取间隔 60 秒 → 等待 1 分钟 → 「历史」tab 出现新日志
6. 设置定时间隔 30（应被后端拒绝）
7. 删除任一数据源，侧栏自动消失

- [ ] **Step 5：提交**

```bash
git add frontend/src/App.vue \
        frontend/src/components/Sidebar.vue \
        frontend/src/components/WorkspaceNode.vue
git commit -m "feat(ds): App 视图态/Sidebar 透传/WorkspaceNode 渲染活数据源"
```

---

## Task 22：端到端验收

**Files:** 无代码改动，仅手测。

- [ ] **Step 1：启动完整环境**

```bash
./start.sh
```

- [ ] **Step 2：覆盖所有路径**

依次完成：

| 类型 | 操作 | 期望 |
|---|---|---|
| MySQL | 创建 → 测试连接 → 表列表 → 预览 → SQL `SELECT 1` → 编辑 host → 删除 | 全部通过 |
| MySQL | SQL 写 `DELETE FROM x` | 400「仅允许只读」 |
| PgSQL | 同 MySQL | 全部通过 |
| 文件 | 上传 PDF / TXT / MD → 内容预览翻页 → 下载原文件 → 删除 | 文件预览正确，磁盘 `datasource-files/<id>/` 在删除后消失 |
| HTTPS | 创建 `https://httpbin.org/get` GET → 立即执行 → 历史 | 状态码 200，历史出现新记录 |
| HTTPS | 定时拉取 60 秒 → 等 60 秒 → 历史 | 多一条记录；后端日志显示 `ds-sched` |
| HTTPS | 创建 `http://localhost:8000/` | 执行时被 SSRF 拒 |
| HTTPS | 定时间隔填 30 | 后端 400「不得小于 60 秒」 |

- [ ] **Step 3：手测重启后定时任务恢复**

```bash
# 启用某 https_api 定时拉取后，重启后端
# Ctrl+C 后再次 ./start.sh
# 检查后端日志应打出 [ds-sched] registered id=...
```

Expected：之前启用的定时任务在重启后自动恢复。

- [ ] **Step 4：合并提交（如有零散修复）**

如本步骤无需任何代码改动，跳过；否则：

```bash
git add -A
git commit -m "fix(ds): 端到端验收发现的细节调整"
```

---

## Plan Self-Review

### Spec 覆盖检查

- ✅ 数据模型扩展（`config_json` / `status` / ...）→ Task 1
- ✅ `data_source_fetch_log` 表 → Task 1
- ✅ DataSourcePO / FetchLogPO → Task 2
- ✅ 所有 REST 端点 → Task 14（含通用 / 数据库 / 文件 / HTTPS）
- ✅ SQL 只读护栏 + LIMIT → Task 7
- ✅ HTTPS SSRF + 响应截断阶梯 → Task 8
- ✅ 文件落盘 + 抽文本 → Task 9
- ✅ 定时拉取 + 启动恢复 + 连续失败停用 → Task 12
- ✅ 敏感字段遮蔽（list 返回时） + detail 端点返原值 → Task 10 / 13
- ✅ 视图态 `datasource` → Task 21
- ✅ Sidebar 「➕ 添加数据源」 + 图标 + 状态点 → Task 21
- ✅ CreateDialog（4 张卡片 + 表单）→ Task 17
- ✅ DetailView 按 kind 渲染不同 tab → Task 20
- ✅ 工作空间隔离（ensureOwnership） → Task 13
- ✅ MySQL 驱动依赖 → Task 4

### 已修复占位 / 一致性问题

- 修复：DTO 字段名（`durationMs` vs `latencyMs`）在 Task 5 / Task 7 / Task 13 中保持一致
- 修复：`HttpExecuteResponse.success` 在 schedule 和 service 中均按 `2xx-3xx` 判断
- 修复：`tryScheduleFromConfig` 在 Task 13 中明确说明从 config 中读 schedule.enabled

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-27-data-source-connectors.md`. Two execution options:

**1. Subagent-Driven (recommended)** — 我每个任务派出独立 subagent，任务间审阅，迭代快
**2. Inline Execution** — 在当前会话内按 executing-plans 批量推进，逢检查点暂停

Which approach?

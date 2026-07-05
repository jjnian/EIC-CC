package com.tuiyan.backend.service;

import com.tuiyan.backend.entity.DataSourceFetchLogPO;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.model.dto.*;
import com.tuiyan.backend.repository.DataSourceFetchLogRepository;
import com.tuiyan.backend.repository.DataSourceRepository;
import com.tuiyan.backend.service.connector.HttpScheduler;
import com.tuiyan.backend.service.connector.JdbcConnectorService;
import com.tuiyan.backend.service.connector.SourceKind;
import com.tuiyan.backend.service.connector.SourceKindHandler;
import com.tuiyan.backend.service.connector.SourceKindHandlerRegistry;
import org.springframework.stereotype.Service;

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
    private final HttpScheduler scheduler;
    private final SchemaInfoDtoMapper schemaInfoDtoMapper;
    /** 按 kind 分派「测试 / 导出文档 / 生命周期」的处理器注册表(加一种数据源 = 加一个 handler)。 */
    private final SourceKindHandlerRegistry handlers;

    public DataSourceService(DataSourceRepository repo,
                             DataSourceFetchLogRepository logRepo,
                             JdbcConnectorService jdbc,
                             HttpScheduler scheduler,
                             SchemaInfoDtoMapper schemaInfoDtoMapper,
                             SourceKindHandlerRegistry handlers) {
        this.repo = repo;
        this.logRepo = logRepo;
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.schemaInfoDtoMapper = schemaInfoDtoMapper;
        this.handlers = handlers;
    }

    // ---------- 通用 CRUD ----------

    public Map<String, Object> create(DataSourceCreateRequest req) {
        String kind = req.getKind();
        validateKind(kind);
        DataSourcePO po = repo.create(null, kind, req.getName(), null, null, req.getConfig());
        SourceKindHandler h = handlers.resolve(kind);          // 如 https 按 schedule 注册定时拉取
        if (h != null) h.onPersist(po, req.getConfig());
        return findFull(po.getId());
    }

    public Map<String, Object> update(String id, DataSourceUpdateRequest req) {
        DataSourcePO po = ensureOwnership(id);
        // 遮蔽字段合并：表单提交 ******** / 留空表示沿用原密码/token，避免把遮蔽串写进库
        Map<String, Object> merged = DataSourceRepository.mergeMaskedConfig(req.getConfig(), repo.readConfig(po));
        boolean ok = repo.updateConfig(id, req.getName(), merged);
        if (!ok) throw new IllegalStateException("更新失败");
        SourceKindHandler h = handlers.resolve(po.getKind());  // 如 https 重新注册定时拉取
        if (h != null) h.onPersist(po, merged);
        return findFull(id);
    }

    public boolean delete(String id) {
        // 数据源为全局公共资源：任意工作空间均可删除。
        DataSourcePO po = repo.findById(id);
        if (po == null) return false;
        SourceKindHandler h = handlers.resolve(po.getKind());  // 如 https 取消定时、file 清理落桶原件
        if (h != null) h.onDelete(po);
        return repo.delete(id);
    }

    /** 含 config 全字段的单条详情（密码/token 遮蔽为 ********，编辑表单留空/保留遮蔽串即沿用原值）。 */
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
        out.put("config", DataSourceRepository.maskConfig(repo.readConfig(po)));
        return out;
    }

    // ---------- 连接测试 ----------

    public DataSourceTestResponse test(String id) {
        DataSourcePO po = ensureOwnership(id);
        SourceKindHandler h = handlers.resolve(po.getKind());
        DataSourceTestResponse r = h != null
                ? h.test(po.getKind(), repo.readConfig(po))
                : new DataSourceTestResponse(true, "无需测试", 0);
        repo.markStatus(id, r.isSuccess() ? "connected" : "error",
                r.isSuccess() ? null : r.getMessage());
        return r;
    }

    /** 创建前的"立即测试"：不入库，仅用 kind+config 调对应处理器。 */
    public DataSourceTestResponse testInline(String kind, Map<String, Object> config) {
        validateKind(kind);
        SourceKindHandler h = handlers.resolve(kind);
        return h != null ? h.test(kind, config)
                : new DataSourceTestResponse(false, "该 kind 不支持测试", null);
    }

    // ---------- 数据库专用 ----------

    public List<String> listTables(String id) {
        DataSourcePO po = ensureOwnership(id);
        requireJdbc(po);
        return jdbc.listTables(po.getKind(), repo.readConfig(po));
    }

    public TablePreviewResponse previewTable(String id, String table, int limit) {
        DataSourcePO po = ensureOwnership(id);
        requireJdbc(po);
        return jdbc.previewTable(po.getKind(), repo.readConfig(po), table, limit);
    }

    public SqlExecuteResponse executeSql(String id, SqlExecuteRequest req) {
        DataSourcePO po = ensureOwnership(id);
        requireJdbc(po);
        int limit = req.getLimit() == null ? JdbcConnectorService.DEFAULT_LIMIT : req.getLimit();
        return jdbc.executeSql(po.getKind(), repo.readConfig(po), req.getSql(), limit);
    }

    /**
     * 血缘值包含检验：验证 child.col 的值是否都能在 parent.col 中找到（推断血缘边的「数据证据」）。
     * 只读、采样、超时受限；仅数据库类数据源可用。
     */
    public JdbcConnectorService.ContainmentCheckResponse verifyContainment(
            String id, String childTable, String childColumn,
            String parentTable, String parentColumn, int sampleLimit) {
        DataSourcePO po = ensureOwnership(id);
        requireJdbc(po);
        return jdbc.verifyContainment(po.getKind(), repo.readConfig(po),
                childTable, childColumn, parentTable, parentColumn, sampleLimit);
    }

    /**
     * 内省 schema：返回 {kind, database, tables:[{name, comment, columns, foreignKeys, uniqueKeys, estimatedRows}]}。
     * <p>给前端"schema 预览"或"提取本体"按钮决策用，也是一键提取本体的可视化输入。
     */
    public Map<String, Object> introspectSchema(String id) {
        DataSourcePO po = ensureOwnership(id);
        requireJdbc(po);
        JdbcConnectorService.DatabaseSchemaInfo info =
                jdbc.introspectSchema(po.getKind(), repo.readConfig(po), 500);
        return schemaInfoDtoMapper.toMap(info);
    }

    /** 内省 schema 的原始结构（含归属校验）：Schema 漂移检测等内部比对用，不经 DTO 转换。 */
    public JdbcConnectorService.DatabaseSchemaInfo introspectSchemaInfo(String id) {
        DataSourcePO po = ensureOwnership(id);
        requireJdbc(po);
        return jdbc.introspectSchema(po.getKind(), repo.readConfig(po), 500);
    }

    /** 全库内省（放开 500 上限，含归属校验）：面向「千张/万张表」的确定性结构建图。 */
    public JdbcConnectorService.DatabaseSchemaInfo introspectSchemaInfoFull(String id) {
        DataSourcePO po = ensureOwnership(id);
        requireJdbc(po);
        return jdbc.introspectSchemaFull(po.getKind(), repo.readConfig(po));
    }

    /** 数据源展示名（供结构建图给模型/节点标注来源）。 */
    public String sourceName(String id) {
        return ensureOwnership(id).getName();
    }

    /** 抽取到经验库的通用文档：数据源名 + 标题 + Markdown 正文 + 标签。 */
    /** origin: jdbc 库结构导出 = "ddl"（建图走 schema 专用规则）；文件转写/HTTP 接口 = "datasource"（普通散文抽取）。 */
    public record SourceDocExport(String sourceName, String title, String content, String tags, String origin) {}

    /**
     * 把「任意类型」的数据源抽取成一篇可入经验库的 Markdown 文档：按 kind 委派给对应
     * {@link SourceKindHandler}（JDBC→DDL、HTTPS→请求配置+响应样例、文件→文本/转写）。
     * 无对应处理器时抛 {@link IllegalArgumentException}（由全局处理器映射为 400）。
     */
    public SourceDocExport exportSourceDoc(String id, int sampleRows) {
        DataSourcePO po = ensureOwnership(id);
        SourceKindHandler h = handlers.resolve(po.getKind());
        if (h == null) {
            throw new IllegalArgumentException("该数据源类型(" + po.getKind() + ")不支持抽取到经验库");
        }
        return h.exportDoc(po, repo.readConfig(po), sampleRows);
    }

    // ---------- HTTPS 专用 ----------

    public HttpExecuteResponse executeHttp(String id) {
        DataSourcePO po = ensureOwnership(id);
        requireKindIn(po, SourceKind.HTTPS_API);
        return scheduler.runOnce(id);
    }

    public List<DataSourceFetchLogPO> listLogs(String id) {
        ensureOwnership(id);
        return logRepo.listLatest(id);
    }

    public void schedule(String id, HttpScheduleRequest req) {
        DataSourcePO po = ensureOwnership(id);
        requireKindIn(po, SourceKind.HTTPS_API);
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

    // ---------- 通用辅助 ----------

    // file_stored 已不再作为可创建类型（文件上传迁移至经验库）；遗留行仍可删除。
    private static void validateKind(String kind) {
        if (!SourceKind.CREATABLE.contains(kind)) {
            throw new IllegalArgumentException("不支持的 kind: " + kind);
        }
    }

    /**
     * 取出数据源（全局公共资源，不再按工作空间隔离）：任意工作空间均可查看 / 查表 / 写 SQL / 编辑。
     * 沿用原方法名以减少调用点改动，语义已放宽为「存在即可」。
     */
    private DataSourcePO ensureOwnership(String id) {
        DataSourcePO po = repo.findById(id);
        if (po == null) throw new IllegalArgumentException("数据源不存在: " + id);
        return po;
    }

    private static void requireKindIn(DataSourcePO po, String... kinds) {
        for (String k : kinds) if (k.equals(po.getKind())) return;
        throw new IllegalArgumentException("当前数据源 kind=" + po.getKind() + " 不支持该操作");
    }

    /** 要求数据源为关系型数据库（走 JDBC 连接器）。 */
    private static void requireJdbc(DataSourcePO po) {
        if (!SourceKind.isJdbc(po.getKind())) {
            throw new IllegalArgumentException("当前数据源 kind=" + po.getKind() + " 不支持该操作");
        }
    }
}

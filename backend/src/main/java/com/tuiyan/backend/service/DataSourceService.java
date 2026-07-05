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
import com.tuiyan.backend.service.connector.SourceKind;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final HttpConnectorService http;
    private final HttpScheduler scheduler;
    private final FileStoredService fileStored;
    private final SchemaInfoDtoMapper schemaInfoDtoMapper;
    private final com.tuiyan.backend.service.llm.DdlRenderer ddlRenderer;
    private final com.tuiyan.backend.service.llm.SchemaGraphFragmentRenderer schemaFragmentRenderer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DataSourceService(DataSourceRepository repo,
                             DataSourceFetchLogRepository logRepo,
                             JdbcConnectorService jdbc,
                             HttpConnectorService http,
                             HttpScheduler scheduler,
                             FileStoredService fileStored,
                             SchemaInfoDtoMapper schemaInfoDtoMapper,
                             com.tuiyan.backend.service.llm.DdlRenderer ddlRenderer,
                             com.tuiyan.backend.service.llm.SchemaGraphFragmentRenderer schemaFragmentRenderer) {
        this.repo = repo;
        this.logRepo = logRepo;
        this.jdbc = jdbc;
        this.http = http;
        this.scheduler = scheduler;
        this.fileStored = fileStored;
        this.schemaInfoDtoMapper = schemaInfoDtoMapper;
        this.ddlRenderer = ddlRenderer;
        this.schemaFragmentRenderer = schemaFragmentRenderer;
    }

    // ---------- 通用 CRUD ----------

    public Map<String, Object> create(DataSourceCreateRequest req) {
        String kind = req.getKind();
        validateKind(kind);
        DataSourcePO po = repo.create(null, kind, req.getName(), null, null, req.getConfig());
        // https_api 若 schedule.enabled=true，立即注册
        if (SourceKind.HTTPS_API.equals(kind)) tryScheduleFromConfig(po.getId(), req.getConfig());
        return findFull(po.getId());
    }

    public Map<String, Object> update(String id, DataSourceUpdateRequest req) {
        DataSourcePO po = ensureOwnership(id);
        // 遮蔽字段合并：表单提交 ******** / 留空表示沿用原密码/token，避免把遮蔽串写进库
        Map<String, Object> merged = DataSourceRepository.mergeMaskedConfig(req.getConfig(), repo.readConfig(po));
        boolean ok = repo.updateConfig(id, req.getName(), merged);
        if (!ok) throw new IllegalStateException("更新失败");
        if (SourceKind.HTTPS_API.equals(po.getKind())) {
            scheduler.cancel(id);
            tryScheduleFromConfig(id, merged);
        }
        return findFull(id);
    }

    public boolean delete(String id) {
        // 数据源为全局公共资源：任意工作空间均可删除。
        DataSourcePO po = repo.findById(id);
        if (po == null) return false;
        scheduler.cancel(id);
        if (SourceKind.FILE_STORED.equals(po.getKind())) fileStored.deleteFiles(id);
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
        DataSourceTestResponse r = dispatchTest(po);
        repo.markStatus(id, r.isSuccess() ? "connected" : "error",
                r.isSuccess() ? null : r.getMessage());
        return r;
    }

    /** 创建前的"立即测试"：不入库，仅用 kind+config 调连接器。 */
    public DataSourceTestResponse testInline(String kind, Map<String, Object> config) {
        validateKind(kind);
        if (SourceKind.isJdbc(kind)) {
            return jdbc.test(kind, config);
        }
        if (SourceKind.HTTPS_API.equals(kind)) {
            HttpExecuteResponse r = http.execute(config);
            return new DataSourceTestResponse(
                    r.isSuccess(),
                    r.isSuccess() ? "HTTP " + r.getStatusCode() : r.getErrorMsg(),
                    r.getDurationMs());
        }
        return new DataSourceTestResponse(false, "该 kind 不支持测试", null);
    }

    private DataSourceTestResponse dispatchTest(DataSourcePO po) {
        Map<String, Object> cfg = repo.readConfig(po);
        if (SourceKind.isJdbc(po.getKind())) {
            return jdbc.test(po.getKind(), cfg);
        }
        if (SourceKind.HTTPS_API.equals(po.getKind())) {
            HttpExecuteResponse r = http.execute(cfg);
            return new DataSourceTestResponse(
                    r.isSuccess(),
                    r.isSuccess() ? "HTTP " + r.getStatusCode() : r.getErrorMsg(),
                    r.getDurationMs());
        }
        return new DataSourceTestResponse(true, "无需测试", 0);
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

    /** 采样的最大表数与每表最大行数：防大库打太多查询 / 经验正文过长。 */
    private static final int SAMPLE_MAX_TABLES = 60;
    private static final int SAMPLE_MAX_PER_TABLE = 10;

    /**
     * DDL 导出结果：数据源名 + 库名 + 渲染好的 DDL 文本 + 对象（表/视图）数量 + 是否含样例数据 +
     * 外键血缘的结构化图片段注释块（可直接附到文档末尾；无外键时为空串）。
     */
    public record DdlExport(String sourceName, String database, String ddl, int objectCount,
                            boolean withSamples, String graphBlock) {}

    /** 不含样例数据的导出（向后兼容）。 */
    public DdlExport exportDdl(String id) {
        return exportDdl(id, 0);
    }

    /**
     * 导出某个数据库数据源的 DDL（CREATE TABLE / VIEW），供「保存到经验库」使用。
     * <p>仅 mysql / pgsql；按当前工作空间做归属校验。
     * @param sampleRows 每张基表附带的样例数据行数；&le;0 表示不抽样例。
     */
    public DdlExport exportDdl(String id, int sampleRows) {
        DataSourcePO po = ensureOwnership(id);
        requireJdbc(po);
        Map<String, Object> cfg = repo.readConfig(po);
        JdbcConnectorService.DatabaseSchemaInfo info = jdbc.introspectSchema(po.getKind(), cfg, 500);
        Map<String, JdbcConnectorService.TableSample> samples = Map.of();
        if (sampleRows > 0) {
            samples = jdbc.sampleRows(po.getKind(), cfg, info.tables(),
                    Math.min(sampleRows, SAMPLE_MAX_PER_TABLE), SAMPLE_MAX_TABLES);
        }
        String ddl = ddlRenderer.render(info, samples);
        // 外键 = 引用血缘 ground truth：确定性直出结构化片段（source=derived/confidence=1.0），
        // 建图时免 LLM 重抽直接合并；无外键则为空串。
        String graphBlock = schemaFragmentRenderer.renderEmbeddedBlock(info);
        return new DdlExport(po.getName(), info.database(), ddl, info.tables().size(), !samples.isEmpty(), graphBlock);
    }

    /** 抽取到经验库的通用文档：数据源名 + 标题 + Markdown 正文 + 标签。 */
    /** origin: jdbc 库结构导出 = "ddl"（建图走 schema 专用规则）；文件转写/HTTP 接口 = "datasource"（普通散文抽取）。 */
    public record SourceDocExport(String sourceName, String title, String content, String tags, String origin) {}

    /**
     * 把「任意类型」的数据源抽取成一篇可入经验库的 Markdown 文档：
     * <ul>
     *   <li>关系型数据库（JDBC）→ DDL（CREATE TABLE/VIEW，sampleRows&gt;0 附样例数据）；</li>
     *   <li>HTTPS 接口 → 请求配置（脱敏）+ 最近一次响应样例（无历史则即时执行一次）。</li>
     * </ul>
     * 其余类型暂不支持，抛 {@link IllegalArgumentException}（由全局处理器映射为 400）。
     */
    public SourceDocExport exportSourceDoc(String id, int sampleRows) {
        DataSourcePO po = ensureOwnership(id);
        String kind = po.getKind();
        if (SourceKind.isJdbc(kind)) {
            DdlExport e = exportDdl(id, sampleRows);
            String title = "「" + e.sourceName() + "」数据库 DDL";
            String content = "# " + title + "\n\n"
                    + "> 库: `" + e.database() + "` · 对象数: " + e.objectCount()
                    + (e.withSamples() ? " · 含样例数据" : "")
                    + (e.graphBlock().isBlank() ? "" : " · 含外键血缘(确定性)")
                    + " · 由数据源结构内省自动生成\n\n"
                    + "```sql\n" + e.ddl() + "\n```\n"
                    // 外键血缘的结构化图片段（隐藏注释）：建图时直连合并，人读 markdown 不受影响
                    + e.graphBlock();
            return new SourceDocExport(e.sourceName(), title, content, "DDL,schema", "ddl");
        }
        if (SourceKind.HTTPS_API.equals(kind)) {
            return exportHttpDoc(po);
        }
        // 文件类数据源（含历史 file_stored、抽取流程产生的音频）：用已抽取/归档的纯文本或转写正文作正文
        String text = fileStored.readText(repo.readConfig(po), 0, 200_000);
        if (text == null || text.isBlank()) {
            text = readExtraTranscript(po);   // 音频等：转写正文落在 extra_json.transcript（非落桶）
        }
        if (text != null && !text.isBlank()) {
            String title = "「" + po.getName() + "」文件";
            String content = "# " + title + "\n\n> 由文件数据源抽取的文本自动生成\n\n" + text;
            return new SourceDocExport(po.getName(), title, content, "file", "datasource");
        }
        throw new IllegalArgumentException("该数据源类型(" + kind + ")没有可抽取到经验库的内容");
    }

    /** HTTPS 接口 → 文档：脱敏请求配置 + 最近一次响应（无历史日志则即时执行一次）。 */
    private SourceDocExport exportHttpDoc(DataSourcePO po) {
        String id = po.getId();
        Map<String, Object> cfg = DataSourceRepository.maskConfig(repo.readConfig(po));

        Integer status; Integer ms; String body; Long when; boolean ok; String err;
        DataSourceFetchLogPO latest = logRepo.listLatest(id).stream().findFirst().orElse(null);
        if (latest != null) {
            status = latest.getStatusCode(); ms = latest.getDurationMs(); body = latest.getResponseBody();
            when = latest.getFetchedAt(); ok = Boolean.TRUE.equals(latest.getSuccess()); err = latest.getErrorMsg();
        } else {
            HttpExecuteResponse r = scheduler.runOnce(id);   // 无历史：即时拉一次拿到样例
            status = r.getStatusCode(); ms = r.getDurationMs(); body = r.getBody();
            when = System.currentTimeMillis(); ok = r.isSuccess(); err = r.getErrorMsg();
        }

        String title = "「" + po.getName() + "」HTTP 接口";
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n")
          .append("> 类型: HTTPS 接口 · 由数据源配置与最近一次响应自动生成\n\n")
          .append("## 请求配置\n\n")
          .append("```json\n").append(toPrettyJson(cfg)).append("\n```\n\n")
          .append("## 最近一次响应\n\n");
        if (ok) {
            sb.append("- 状态: HTTP ").append(status).append(" · 耗时 ").append(ms).append("ms");
            if (when != null) sb.append(" · 时间 ").append(new Date(when));
            sb.append("\n\n");
            String sample = body == null ? "" : body;
            int cap = 20_000;
            if (sample.length() > cap) sample = sample.substring(0, cap) + "\n…（响应过长已截断）";
            sb.append("```json\n").append(sample).append("\n```\n");
        } else {
            sb.append("- 调用失败: ").append(err == null || err.isBlank() ? "未知错误" : err).append("\n");
        }
        return new SourceDocExport(po.getName(), title, sb.toString(), "http,api", "datasource");
    }

    /** 从 data_source.extra_json 读取音频转写正文（无则 null）。 */
    private String readExtraTranscript(DataSourcePO po) {
        String extra = po.getExtraJson();
        if (extra == null || extra.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode t = objectMapper.readTree(extra).get("transcript");
            return (t != null && t.isTextual() && !t.asText().isBlank()) ? t.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String toPrettyJson(Object v) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
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

    private void tryScheduleFromConfig(String id, Map<String, Object> cfg) {
        if (cfg == null) return;
        Object schedObj = cfg.get("schedule");
        if (!(schedObj instanceof Map<?, ?> sched)) return;
        if (!Boolean.TRUE.equals(sched.get("enabled"))) return;
        Object iv = sched.get("intervalSec");
        if (iv instanceof Number n) scheduler.register(id, n.intValue());
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

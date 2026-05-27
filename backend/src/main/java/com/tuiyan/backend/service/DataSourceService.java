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

package com.tuiyan.backend.controller;

import com.tuiyan.backend.config.ResourceNotFoundException;
import com.tuiyan.backend.entity.NodeDataBindingPO;
import com.tuiyan.backend.model.dto.ApiResult;
import com.tuiyan.backend.model.dto.SqlExecuteRequest;
import com.tuiyan.backend.model.dto.SqlExecuteResponse;
import com.tuiyan.backend.model.dto.SuccessCountResponse;
import com.tuiyan.backend.model.dto.TablePreviewResponse;
import com.tuiyan.backend.repository.NodeDataBindingRepository;
import com.tuiyan.backend.service.DataSourceService;
import com.tuiyan.backend.service.NodeStateScheduler;
import com.tuiyan.backend.service.NodeStateService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点数据供血绑定端点：把已建好的本体血缘图节点绑定到数据源的表/列，并按绑定取数供血；
 * 态势层在此之上——绑定可配置「状态查询 + 阈值规则」成为状态源，定时刷新节点运行状态。
 * <p>这是新数据流的第二阶段——本体血缘图由经验库文件构建后，数据源在这里只负责
 * 为图节点绑定真实数据来源（table/column 映射）并取数，不再参与建图。
 */
@RestController
@RequestMapping("/api/node-bindings")
public class NodeBindingController {

    private final NodeDataBindingRepository repo;
    private final DataSourceService dataSourceService;
    private final NodeStateService stateService;
    private final NodeStateScheduler stateScheduler;

    public NodeBindingController(NodeDataBindingRepository repo, DataSourceService dataSourceService,
                                 NodeStateService stateService, NodeStateScheduler stateScheduler) {
        this.repo = repo;
        this.dataSourceService = dataSourceService;
        this.stateService = stateService;
        this.stateScheduler = stateScheduler;
    }

    /** 列出某模型（可选某节点）下的供血绑定。 */
    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam String modelId,
                                                      @RequestParam(required = false) String nodeId) {
        return ApiResult.ok(repo.list(modelId, nodeId));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String modelId = str(body.get("modelId"));
        String nodeId = str(body.get("nodeId"));
        String dataSourceId = str(body.get("dataSourceId"));
        if (modelId.isBlank() || nodeId.isBlank() || dataSourceId.isBlank()) {
            throw new IllegalArgumentException("缺少 modelId / nodeId / dataSourceId");
        }
        Map<String, Object> created = repo.create(modelId, nodeId, dataSourceId,
                strOrNull(body.get("tableName")), strOrNull(body.get("columnMap")), strOrNull(body.get("filterSql")));
        return ApiResult.ok(created);
    }

    @PutMapping("/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable String id,
                                                  @RequestBody Map<String, Object> body) {
        Map<String, Object> updated = repo.update(id,
                strOrNull(body.get("dataSourceId")), strOrNull(body.get("tableName")),
                strOrNull(body.get("columnMap")), strOrNull(body.get("filterSql")));
        if (updated == null) {
            throw new ResourceNotFoundException("绑定不存在");
        }
        return ApiResult.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ApiResult<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = repo.delete(id);
        if (ok) {
            stateScheduler.cancel(id);
            stateService.deleteByBinding(id);
        }
        return ApiResult.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    // ---------- 态势层：状态源配置 / 刷新 / 查询 ----------

    /**
     * 配置绑定的状态源：{statusQuery, statusRules, statusEnabled, statusIntervalSec}。
     * statusQuery 为只读 SQL（首行首列作为状态值）；启用即注册定时刷新。
     */
    @PutMapping("/{id}/status-config")
    public ApiResult<Map<String, Object>> statusConfig(@PathVariable String id,
                                                        @RequestBody Map<String, Object> body) {
        String query = strOrNull(body.get("statusQuery"));
        String rules = strOrNull(body.get("statusRules"));
        boolean enabled = Boolean.TRUE.equals(body.get("statusEnabled"));
        Integer interval = body.get("statusIntervalSec") instanceof Number n
                ? Math.max(NodeStateScheduler.MIN_INTERVAL_SEC, n.intValue()) : null;
        if (enabled && (query == null || query.isBlank())) {
            throw new IllegalArgumentException("启用状态刷新前请先填写状态查询 SQL");
        }
        Map<String, Object> updated = repo.updateStatusConfig(id, query, rules, enabled, interval);
        if (updated == null) {
            throw new ResourceNotFoundException("绑定不存在");
        }
        if (enabled) {
            stateScheduler.register(id, interval == null ? NodeStateScheduler.MIN_INTERVAL_SEC : interval);
        } else {
            stateScheduler.cancel(id);
        }
        return ApiResult.ok(updated);
    }

    /** 手动刷新一次状态（含归属校验），返回最新状态。 */
    @PostMapping("/{id}/status/refresh")
    public ApiResult<Map<String, Object>> refreshStatus(@PathVariable String id) {
        if (repo.findScoped(id) == null) {
            throw new ResourceNotFoundException("绑定不存在");
        }
        return ApiResult.ok(stateService.refresh(id));
    }

    /** 某模型下全部节点的当前状态（态势画布轮询用）。 */
    @GetMapping("/states")
    public ApiResult<List<Map<String, Object>>> states(@RequestParam String modelId) {
        return ApiResult.ok(stateService.statesOfModel(modelId));
    }

    /** 某绑定的状态历史（新→旧）。 */
    @GetMapping("/{id}/status/history")
    public ApiResult<List<Map<String, Object>>> statusHistory(@PathVariable String id,
                                                               @RequestParam(defaultValue = "100") int limit) {
        return ApiResult.ok(stateService.history(id, limit));
    }

    /**
     * 按绑定取数供血：从绑定的数据源 + 表读取数据（可选 filterSql 过滤），返回 {columns, rows, rowCount, truncated}。
     * 无过滤走表预览；有过滤走只读 SQL（SELECT * FROM table WHERE filter，连接器强制只读 + LIMIT 兜底）。
     */
    @PostMapping("/{id}/fetch")
    public ApiResult<Map<String, Object>> fetch(@PathVariable String id,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        NodeDataBindingPO po = repo.findScoped(id);
        if (po == null) {
            throw new ResourceNotFoundException("绑定不存在");
        }
        String table = po.getTableName();
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("该绑定未指定表名");
        }
        int limit = 50;
        if (body != null && body.get("limit") instanceof Number n) limit = Math.max(1, Math.min(500, n.intValue()));

        Map<String, Object> out = new LinkedHashMap<>();
        String filter = po.getFilterSql();
        if (filter == null || filter.isBlank()) {
            TablePreviewResponse r = dataSourceService.previewTable(po.getDataSourceId(), table, limit);
            out.put("columns", r.getColumns());
            out.put("rows", r.getRows());
            out.put("rowCount", r.getRowCount());
            out.put("truncated", r.isTruncated());
        } else {
            SqlExecuteRequest req = new SqlExecuteRequest();
            req.setSql("SELECT * FROM " + table + " WHERE " + filter);
            req.setLimit(limit);
            SqlExecuteResponse r = dataSourceService.executeSql(po.getDataSourceId(), req);
            out.put("columns", r.getColumns());
            out.put("rows", r.getRows());
            out.put("rowCount", r.getRowCount());
            out.put("truncated", r.isTruncated());
        }
        out.put("dataSourceId", po.getDataSourceId());
        out.put("tableName", table);
        return ApiResult.ok(out);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String strOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}

package com.tuiyan.backend.controller;

import com.tuiyan.backend.entity.NodeDataBindingPO;
import com.tuiyan.backend.model.dto.SqlExecuteRequest;
import com.tuiyan.backend.model.dto.SqlExecuteResponse;
import com.tuiyan.backend.model.dto.SuccessCountResponse;
import com.tuiyan.backend.model.dto.TablePreviewResponse;
import com.tuiyan.backend.repository.NodeDataBindingRepository;
import com.tuiyan.backend.service.DataSourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点数据供血绑定端点：把已建好的本体血缘图节点绑定到数据源的表/列，并按绑定取数供血。
 * <p>这是新数据流的第二阶段——本体血缘图由经验库文件构建后，数据源在这里只负责
 * 为图节点绑定真实数据来源（table/column 映射）并取数，不再参与建图。
 */
@RestController
@RequestMapping("/api/node-bindings")
public class NodeBindingController {

    private final NodeDataBindingRepository repo;
    private final DataSourceService dataSourceService;

    public NodeBindingController(NodeDataBindingRepository repo, DataSourceService dataSourceService) {
        this.repo = repo;
        this.dataSourceService = dataSourceService;
    }

    /** 列出某模型（可选某节点）下的供血绑定。 */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam String modelId,
                                                          @RequestParam(required = false) String nodeId) {
        return ResponseEntity.ok(repo.list(modelId, nodeId));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String modelId = str(body.get("modelId"));
        String nodeId = str(body.get("nodeId"));
        String dataSourceId = str(body.get("dataSourceId"));
        if (modelId.isBlank() || nodeId.isBlank() || dataSourceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 modelId / nodeId / dataSourceId"));
        }
        Map<String, Object> created = repo.create(modelId, nodeId, dataSourceId,
                strOrNull(body.get("tableName")), strOrNull(body.get("columnMap")), strOrNull(body.get("filterSql")));
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        Map<String, Object> updated = repo.update(id,
                strOrNull(body.get("dataSourceId")), strOrNull(body.get("tableName")),
                strOrNull(body.get("columnMap")), strOrNull(body.get("filterSql")));
        return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = repo.delete(id);
        return ResponseEntity.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    /**
     * 按绑定取数供血：从绑定的数据源 + 表读取数据（可选 filterSql 过滤），返回 {columns, rows, rowCount, truncated}。
     * 无过滤走表预览；有过滤走只读 SQL（SELECT * FROM table WHERE filter，连接器强制只读 + LIMIT 兜底）。
     */
    @PostMapping("/{id}/fetch")
    public ResponseEntity<Map<String, Object>> fetch(@PathVariable String id,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        NodeDataBindingPO po = repo.findScoped(id);
        if (po == null) return ResponseEntity.notFound().build();
        String table = po.getTableName();
        if (table == null || table.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "该绑定未指定表名"));
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
        return ResponseEntity.ok(out);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String strOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}

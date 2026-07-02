package com.tuiyan.backend.service;

import com.tuiyan.backend.repository.OntologyModelRepository;
import com.tuiyan.backend.service.connector.JdbcConnectorService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Schema 漂移检测：业务库结构会演进（删表/删列/改名），图不会自己知道。
 * 本服务重新内省指定数据源的最新 schema，与图上节点/边引用的 {@code derived_tables}
 * 及节点属性的 {@code column} 逐一比对，报告已失效的表/列与受其影响的节点/边，
 * 供用户决定修图（删除过时节点、重新导 DDL 增量建图等）。
 * <p>只读；比对为纯内存计算。注意：图上可能混着多个数据源的表，
 * 报告的"缺失表"若实际属于其它数据源可忽略（前端有提示）。
 */
@Service
public class SchemaDriftService {

    private final OntologyModelRepository modelRepo;
    private final DataSourceService dataSourceService;

    public SchemaDriftService(OntologyModelRepository modelRepo, DataSourceService dataSourceService) {
        this.modelRepo = modelRepo;
        this.dataSourceService = dataSourceService;
    }

    /**
     * 比对模型图谱与数据源最新 schema。
     * <p>调用方负责模型归属校验；数据源归属由 {@link DataSourceService} 内部校验。
     * @return {schemaTables, referencedTables, missingTables:[{table,nodes,edges}],
     *          missingColumns:[{table,column,nodes}], okTables}
     */
    public Map<String, Object> detect(String modelId, String dataSourceId) {
        JdbcConnectorService.DatabaseSchemaInfo schema = dataSourceService.introspectSchemaInfo(dataSourceId);

        // 最新 schema 索引：表名(小写) → 列名集合(小写)
        Map<String, Set<String>> live = new HashMap<>();
        for (JdbcConnectorService.TableInfo t : schema.tables()) {
            Set<String> cols = new HashSet<>();
            for (JdbcConnectorService.ColumnInfo c : t.columns()) {
                cols.add(c.name().toLowerCase(Locale.ROOT));
            }
            live.put(t.name().toLowerCase(Locale.ROOT), cols);
        }

        OntologyModelRepository.NodesAndEdges g = modelRepo.loadGraphForVersion(modelId);

        // 缺失表 → 引用它的节点/边；缺失列 → (表,列) → 节点
        Map<String, List<String>> missingTableNodes = new LinkedHashMap<>();
        Map<String, List<String>> missingTableEdges = new LinkedHashMap<>();
        Map<String, List<String>> missingColumnNodes = new LinkedHashMap<>(); // key: table.column
        Set<String> referencedTables = new HashSet<>();
        Set<String> okTables = new HashSet<>();

        for (Map<String, Object> n : g.nodes()) {
            String id = str(n.get("id"));
            String label = str(n.get("label"));
            String display = label == null || label.isBlank() ? id : label;
            List<String> tables = tableList(n.get("derived_tables"));
            List<String> nodeLiveTables = new ArrayList<>();
            for (String t : tables) {
                String key = t.toLowerCase(Locale.ROOT);
                referencedTables.add(key);
                if (live.containsKey(key)) {
                    okTables.add(key);
                    nodeLiveTables.add(key);
                } else {
                    missingTableNodes.computeIfAbsent(t, k -> new ArrayList<>()).add(display);
                }
            }
            // 列检查：节点属性里带 column 的条目，须能在该节点引用的任一现存表里找到；
            // 节点引用的表全部缺失时跳过（已按缺失表报告，不重复报列）
            if (nodeLiveTables.isEmpty()) continue;
            Object attrs = n.get("attributes");
            if (!(attrs instanceof List<?> attrList)) continue;
            for (Object a : attrList) {
                if (!(a instanceof Map<?, ?> am)) continue;
                Object colObj = am.get("column");
                if (colObj == null) continue;
                String col = String.valueOf(colObj).trim().toLowerCase(Locale.ROOT);
                if (col.isEmpty()) continue;
                boolean found = false;
                String firstTable = nodeLiveTables.get(0);
                for (String t : nodeLiveTables) {
                    Set<String> cols = live.get(t);
                    if (cols != null && cols.contains(col)) { found = true; break; }
                }
                if (!found) {
                    missingColumnNodes.computeIfAbsent(firstTable + "." + col, k -> new ArrayList<>()).add(display);
                }
            }
        }
        for (Map<String, Object> e : g.edges()) {
            String display = str(e.get("label"));
            if (display == null || display.isBlank()) display = str(e.get("id"));
            for (String t : tableList(e.get("derived_tables"))) {
                String key = t.toLowerCase(Locale.ROOT);
                referencedTables.add(key);
                if (live.containsKey(key)) okTables.add(key);
                else missingTableEdges.computeIfAbsent(t, k -> new ArrayList<>()).add(display);
            }
        }

        // 汇总输出
        List<Map<String, Object>> missingTables = new ArrayList<>();
        Set<String> allMissing = new java.util.LinkedHashSet<>(missingTableNodes.keySet());
        allMissing.addAll(missingTableEdges.keySet());
        for (String t : allMissing) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("table", t);
            one.put("nodes", dedup(missingTableNodes.getOrDefault(t, List.of())));
            one.put("edges", dedup(missingTableEdges.getOrDefault(t, List.of())));
            missingTables.add(one);
        }
        List<Map<String, Object>> missingColumns = new ArrayList<>();
        for (Map.Entry<String, List<String>> en : missingColumnNodes.entrySet()) {
            int dot = en.getKey().lastIndexOf('.');
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("table", en.getKey().substring(0, dot));
            one.put("column", en.getKey().substring(dot + 1));
            one.put("nodes", dedup(en.getValue()));
            missingColumns.add(one);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("database", schema.database());
        out.put("schemaTables", schema.tables().size());
        out.put("referencedTables", referencedTables.size());
        out.put("okTables", okTables.size());
        out.put("missingTables", missingTables);
        out.put("missingColumns", missingColumns);
        return out;
    }

    private static List<String> tableList(Object derivedTables) {
        List<String> out = new ArrayList<>();
        if (derivedTables instanceof List<?> list) {
            for (Object o : list) {
                String t = o == null ? "" : String.valueOf(o).trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    private static List<String> dedup(List<String> in) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(in));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.AppPaths;
import com.tuiyan.backend.model.PredictionDag;
import com.tuiyan.backend.model.Scenario;
import com.tuiyan.backend.util.JsonAtomic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推演分支（Scenario）服务：列表 / 读 / 写 / 级联删除 + 老数据迁移。
 * <p>每个 Scenario 单独落盘到 {@code ~/.tuiyan/scenarios/<id>.json}，
 * 列表时全量读盘 + 按 modelId 过滤 —— 数据规模小（单用户），不引入索引。
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppPaths appPaths;

    public ScenarioService(AppPaths appPaths) {
        this.appPaths = appPaths;
        File d = appPaths.scenariosDir();
        if (!d.exists()) d.mkdirs();
    }

    /**
     * 按 modelId 列出分支，按 createdAt 倒序。
     * @param modelId 为 null 时返回全部（用于级联删除查找）
     */
    public List<Scenario> listByModel(String modelId) throws IOException {
        File d = appPaths.scenariosDir();
        File[] files = d.listFiles((f, n) -> n.endsWith(".json"));
        List<Scenario> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) {
            try {
                Scenario s = objectMapper.readValue(f, Scenario.class);
                if (modelId == null || modelId.equals(s.getModelId())) {
                    out.add(s);
                }
            } catch (IOException ioe) {
                log.warn("skip malformed scenario file {}: {}", f, ioe.toString());
            }
        }
        out.sort(Comparator.comparingLong(Scenario::getCreatedAt).reversed());
        return out;
    }

    public Scenario get(String id) throws IOException {
        File f = fileFor(id);
        if (!f.exists()) return null;
        return objectMapper.readValue(f, Scenario.class);
    }

    /** 写盘；id / createdAt 缺省时自动补上。 */
    public void save(Scenario s) throws IOException {
        if (s.getId() == null || s.getId().isBlank()) {
            s.setId("sc_" + System.currentTimeMillis());
        }
        if (s.getCreatedAt() == 0L) {
            s.setCreatedAt(System.currentTimeMillis());
        }
        JsonAtomic.write(objectMapper, fileFor(s.getId()), s);
    }

    /**
     * v0.8：级联删除以该 id 为祖先的所有子分支文件。
     * <p>用 parentBranchId 链向上回溯：若某分支沿 parent 链能走到 id，就属于待删除集合。
     * @return 实际删除的文件总数（含本身）
     */
    public int delete(String id) {
        int total = 0;
        try {
            List<Scenario> all = listByModel(null);
            // parentMap: scenarioId → parentBranchId，避免在循环里反复线性查找
            java.util.Map<String, String> parentMap = new java.util.HashMap<>();
            for (Scenario s : all) parentMap.put(s.getId(), s.getParentBranchId());
            List<String> toDelete = new ArrayList<>();
            toDelete.add(id);
            for (Scenario s : all) {
                // 沿 parent 链向上回溯，若能走到 id 就加入待删
                String cur = s.getParentBranchId();
                while (cur != null) {
                    if (cur.equals(id)) {
                        toDelete.add(s.getId());
                        break;
                    }
                    cur = parentMap.get(cur);
                }
            }
            for (String d : toDelete) {
                File f = fileFor(d);
                if (f.exists() && f.delete()) total++;
            }
        } catch (IOException e) {
            // 列表读取失败仍尝试只删本身这个文件，避免完全失败
            log.warn("cascade delete listing failed, falling back to single-file delete for {}", id, e);
            File f = fileFor(id);
            if (f.exists() && f.delete()) total++;
        }
        return total;
    }

    private File fileFor(String id) {
        String safe = id.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return new File(appPaths.scenariosDir(), safe + ".json");
    }

    /**
     * v0.5 → v0.6 数据迁移：把"完整快照分支"转成"仅含 delta 的 dag 结构"。
     * <p>策略：扫描 nodes/edges 中 {@code source=="predicted"} 的节点 / 边作为新 dag 内容，
     * 同时把原顶层 chain 搬到 dag.chain。迁移前把原始文件备份为 .bak，失败可手工恢复。
     * <p>已经是新结构（dag 非空）或完全没有数据的分支会被跳过。
     */
    public Map<String, Integer> migrateAll() throws IOException {
        int migrated = 0, skipped = 0, errors = 0;
        List<Scenario> all = listByModel(null);
        for (Scenario s : all) {
            try {
                // 已经是 v0.6+ 结构 → 跳过
                if (s.getDag() != null) { skipped++; continue; }
                // 老分支但既无 chain 也无 nodes → 没有可迁移的数据
                if (s.getChain() == null && s.getNodes() == null) { skipped++; continue; }

                // 从老 nodes 中筛出 predicted 节点
                List<Map<String, Object>> predNodes = new ArrayList<>();
                if (s.getNodes() != null) {
                    for (Map<String, Object> n : s.getNodes()) {
                        if ("predicted".equals(n.get("source"))) predNodes.add(n);
                    }
                }
                // 同理筛出 predicted 边
                List<Map<String, Object>> predEdges = new ArrayList<>();
                if (s.getEdges() != null) {
                    for (Map<String, Object> e : s.getEdges()) {
                        if ("predicted".equals(e.get("source"))) predEdges.add(e);
                    }
                }

                PredictionDag dag = new PredictionDag();
                dag.setIntent(s.getIntent() != null ? s.getIntent() : "forward");
                dag.setNodes(predNodes);
                dag.setEdges(predEdges);
                dag.setChain(s.getChain() != null ? s.getChain() : new ArrayList<>());
                s.setDag(dag);
                if (s.getIntent() == null) s.setIntent("forward");

                // 备份原始文件为 .bak（仅首次迁移时备份，已存在则跳过避免覆盖更旧备份）
                File orig = fileFor(s.getId());
                if (orig.exists()) {
                    File bak = new File(orig.getAbsolutePath() + ".bak");
                    if (!bak.exists()) {
                        java.nio.file.Files.copy(orig.toPath(), bak.toPath());
                    }
                }

                // 清空 v0.5 顶层冗余字段（dag 中已经包含）
                s.setNodes(null);
                s.setEdges(null);
                save(s);
                migrated++;
            } catch (Exception ex) {
                log.warn("scenario migration failed for id={}: {}", s.getId(), ex.toString(), ex);
                errors++;
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("migrated", migrated);
        out.put("skipped", skipped);
        out.put("errors", errors);
        out.put("total", all.size());
        return out;
    }
}

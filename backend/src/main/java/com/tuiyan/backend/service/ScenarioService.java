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
     * 返回删除的总数（含本身）。
     */
    public int delete(String id) {
        int total = 0;
        try {
            List<Scenario> all = listByModel(null);
            java.util.Map<String, String> parentMap = new java.util.HashMap<>();
            for (Scenario s : all) parentMap.put(s.getId(), s.getParentBranchId());
            List<String> toDelete = new ArrayList<>();
            toDelete.add(id);
            for (Scenario s : all) {
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

    public Map<String, Integer> migrateAll() throws IOException {
        int migrated = 0, skipped = 0, errors = 0;
        List<Scenario> all = listByModel(null);
        for (Scenario s : all) {
            try {
                if (s.getDag() != null) { skipped++; continue; }
                if (s.getChain() == null && s.getNodes() == null) { skipped++; continue; }

                List<Map<String, Object>> predNodes = new ArrayList<>();
                if (s.getNodes() != null) {
                    for (Map<String, Object> n : s.getNodes()) {
                        if ("predicted".equals(n.get("source"))) predNodes.add(n);
                    }
                }
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

                File orig = fileFor(s.getId());
                if (orig.exists()) {
                    File bak = new File(orig.getAbsolutePath() + ".bak");
                    if (!bak.exists()) {
                        java.nio.file.Files.copy(orig.toPath(), bak.toPath());
                    }
                }

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

package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.model.Scenario;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ScenarioService {

    private static final String DIR = "src/main/resources/scenarios";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScenarioService() {
        File d = new File(DIR);
        if (!d.exists()) d.mkdirs();
    }

    public List<Scenario> listByModel(String modelId) throws IOException {
        File d = new File(DIR);
        File[] files = d.listFiles((f, n) -> n.endsWith(".json"));
        List<Scenario> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) {
            try {
                Scenario s = objectMapper.readValue(f, Scenario.class);
                if (modelId == null || modelId.equals(s.getModelId())) {
                    out.add(s);
                }
            } catch (IOException ignored) {
                // skip malformed file
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
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(fileFor(s.getId()), s);
    }

    /**
     * v0.8：级联删除以该 id 为祖先的所有子分支文件。
     * 返回删除的总数（含本身）。
     */
    public int delete(String id) {
        int total = 0;
        try {
            // 先收集所有以 id 为父或更深祖先的分支
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
            File f = fileFor(id);
            if (f.exists() && f.delete()) total++;
        }
        return total;
    }

    private File fileFor(String id) {
        String safe = id.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return new File(DIR + "/" + safe + ".json");
    }
}

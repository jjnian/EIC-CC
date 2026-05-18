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

    public boolean delete(String id) {
        File f = fileFor(id);
        return f.exists() && f.delete();
    }

    private File fileFor(String id) {
        String safe = id.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return new File(DIR + "/" + safe + ".json");
    }
}

package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.AppPaths;
import com.tuiyan.backend.model.HypothesisTemplate;
import com.tuiyan.backend.util.JsonAtomic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class HypothesisTemplateService {

    private static final Logger log = LoggerFactory.getLogger(HypothesisTemplateService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppPaths appPaths;

    public HypothesisTemplateService(AppPaths appPaths) {
        this.appPaths = appPaths;
    }

    public List<HypothesisTemplate> listByModel(String modelId) {
        File d = appPaths.hypothesisTemplatesDir();
        File[] files = d.listFiles((f, n) -> n.endsWith(".json"));
        List<HypothesisTemplate> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) {
            try {
                HypothesisTemplate t = objectMapper.readValue(f, HypothesisTemplate.class);
                if (modelId == null || modelId.isBlank() || modelId.equals(t.getModelId())) {
                    out.add(t);
                }
            } catch (IOException ioe) {
                log.warn("skip malformed template file {}: {}", f, ioe.toString());
            }
        }
        out.sort(Comparator.comparingLong(HypothesisTemplate::getLastUsedAt).reversed());
        return out;
    }

    public void save(HypothesisTemplate t) throws IOException {
        JsonAtomic.write(objectMapper, fileFor(t.getId()), t);
    }

    public void touch(String id) throws IOException {
        File f = fileFor(id);
        if (!f.exists()) return;
        HypothesisTemplate t = objectMapper.readValue(f, HypothesisTemplate.class);
        t.setLastUsedAt(System.currentTimeMillis());
        JsonAtomic.write(objectMapper, f, t);
    }

    public boolean delete(String id) {
        File f = fileFor(id);
        return f.exists() && f.delete();
    }

    private File fileFor(String id) {
        String safe = id.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return new File(appPaths.hypothesisTemplatesDir(), safe + ".json");
    }
}

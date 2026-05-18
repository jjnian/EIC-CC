package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.service.ScenarioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/prefs")
public class PrefsController {

    private static final String PREFS_FILE = "src/main/resources/prefs.json";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScenarioService scenarioService;

    public PrefsController(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @GetMapping
    public ResponseEntity<?> get() {
        try {
            return ResponseEntity.ok(readOrDefault());
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> merged = readOrDefault();
            merged.putAll(body);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(PREFS_FILE), merged);
            return ResponseEntity.ok(merged);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/scenarios")
    public ResponseEntity<?> clearAllScenarios() {
        int n = 0;
        File dir = new File("src/main/resources/scenarios");
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((f, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    if (f.delete()) n++;
                }
            }
        }
        return ResponseEntity.ok(Map.of("deleted", n));
    }

    private Map<String, Object> readOrDefault() throws IOException {
        File f = new File(PREFS_FILE);
        if (!f.exists()) return defaults();
        JsonNode node = objectMapper.readTree(f);
        Map<String, Object> out = new LinkedHashMap<>(defaults());
        if (node.isObject()) {
            node.fields().forEachRemaining(e ->
                    out.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class)));
        }
        return out;
    }

    private Map<String, Object> defaults() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("predictDefaultSteps", 4);
        d.put("predictMinConfidence", 0.3);
        d.put("predictStepDelayMs", 220);
        d.put("showEdgeLabels", true);
        d.put("autoFit", true);
        d.put("graphFontSize", 13);
        d.put("defaultModelConfigId", "");
        return d;
    }
}

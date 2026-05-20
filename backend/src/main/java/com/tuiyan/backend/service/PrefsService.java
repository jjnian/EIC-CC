package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.AppPaths;
import com.tuiyan.backend.util.JsonAtomic;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户偏好(prefs)文件读写 + 默认值合并 + 清空 scenarios。
 */
@Service
public class PrefsService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppPaths appPaths;

    public PrefsService(AppPaths appPaths) {
        this.appPaths = appPaths;
    }

    public Map<String, Object> read() throws IOException {
        File f = appPaths.prefsFile();
        if (!f.exists()) return defaults();
        JsonNode node = objectMapper.readTree(f);
        Map<String, Object> out = new LinkedHashMap<>(defaults());
        if (node.isObject()) {
            node.fields().forEachRemaining(e ->
                    out.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class)));
        }
        return out;
    }

    public Map<String, Object> save(Map<String, Object> body) throws IOException {
        Map<String, Object> merged = read();
        merged.putAll(body);
        JsonAtomic.write(objectMapper, appPaths.prefsFile(), merged);
        return merged;
    }

    /** 删除全部 scenarios 文件。返回删除张数。 */
    public int clearAllScenarios() {
        int n = 0;
        File dir = appPaths.scenariosDir();
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((f, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    if (f.delete()) n++;
                }
            }
        }
        return n;
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

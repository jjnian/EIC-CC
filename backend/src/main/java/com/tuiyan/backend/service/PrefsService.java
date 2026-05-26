package com.tuiyan.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.AppPaths;
import com.tuiyan.backend.entity.ScenarioPO;
import com.tuiyan.backend.mapper.ScenarioMapper;
import com.tuiyan.backend.util.JsonAtomic;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户偏好（prefs）服务：读 / 写 {@code ~/.tuiyan/prefs.json}，附带默认值合并与一键清空 scenarios。
 * <p>prefs 文件不存在时返回 {@link #defaults} 的副本；
 * 已有文件时把磁盘内容按字段合并到默认值之上，保证前端拿到的字段集是"默认 ∪ 用户覆盖"。
 */
@Service
public class PrefsService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppPaths appPaths;
    private final ScenarioMapper scenarioMapper;

    public PrefsService(AppPaths appPaths, ScenarioMapper scenarioMapper) {
        this.appPaths = appPaths;
        this.scenarioMapper = scenarioMapper;
    }

    /**
     * 读 prefs。文件不存在时直接返回默认值；存在时把磁盘字段覆盖到默认值之上，
     * 这样新增默认字段不需要迁移旧 prefs 文件。
     */
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

    /**
     * 保存：先读出当前合并后的 prefs，再把 body 字段覆盖上去并写盘。
     * <p>这种"读-合并-写"模式保证前端只需要传变化的字段，不必每次完整提交所有 prefs。
     */
    public Map<String, Object> save(Map<String, Object> body) throws IOException {
        Map<String, Object> merged = read();
        merged.putAll(body);
        JsonAtomic.write(objectMapper, appPaths.prefsFile(), merged);
        return merged;
    }

    /**
     * 清空全部推演分支（含 DAG / chain / 约束 / 解释，外键级联清子表）。
     * @return 实际删除的分支数
     */
    public int clearAllScenarios() {
        List<ScenarioPO> all = scenarioMapper.selectList(new LambdaQueryWrapper<>());
        int n = 0;
        for (ScenarioPO p : all) {
            if (scenarioMapper.deleteById(p.getId()) > 0) n++;
        }
        return n;
    }

    /** 默认偏好字段；新增前端可调项时在这里加默认值即可，旧 prefs.json 不需要迁移。 */
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

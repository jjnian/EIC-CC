package com.tuiyan.backend.service;

import com.tuiyan.backend.model.Scenario;
import com.tuiyan.backend.repository.ScenarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推演分支（Scenario）服务：列表 / 读 / 写 / 级联删除。
 * <p>v0.5 → v0.6 数据迁移已不再需要（数据库迁移阶段不保留旧 JSON 数据）。
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    private final ScenarioRepository scenarioRepository;

    public ScenarioService(ScenarioRepository scenarioRepository) {
        this.scenarioRepository = scenarioRepository;
    }

    /** 按 modelId 列出分支，按 createdAt 倒序；modelId 为 null 时返回全部。 */
    public List<Scenario> listByModel(String modelId) {
        return scenarioRepository.list(modelId);
    }

    public Scenario get(String id) {
        return scenarioRepository.get(id);
    }

    /** 写入；id / createdAt 缺省时自动补上。 */
    public void save(Scenario s) {
        if (s.getId() == null || s.getId().isBlank()) {
            s.setId("sc_" + System.currentTimeMillis());
        }
        if (s.getCreatedAt() == 0L) {
            s.setCreatedAt(System.currentTimeMillis());
        }
        scenarioRepository.save(s);
    }

    /**
     * 级联删除以该 id 为祖先的所有子分支。
     * @return 实际删除的分支总数（含本身）
     */
    public int delete(String id) {
        return scenarioRepository.delete(id);
    }

    /**
     * 数据迁移接口保留：迁库后无历史数据可迁移，统一返回 0。
     * <p>controller 仍然暴露端点，前端调用时直接得到"无可迁移项"的结果。
     */
    public Map<String, Integer> migrateAll() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("migrated", 0);
        out.put("skipped", 0);
        out.put("errors", 0);
        out.put("total", 0);
        return out;
    }
}

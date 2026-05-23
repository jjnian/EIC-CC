package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.AppPaths;
import com.tuiyan.backend.config.ResourceNotFoundException;
import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.util.JsonAtomic;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 图谱模板服务：管理"图谱样板"的增删查操作。
 * <p>模板与本体模型共用 {@link OntologyModel} 数据结构，但存储在独立的 {@code templates/} 子目录，
 * 列表 / 引用逻辑互不干扰；用户可以把现有图谱另存为模板，下次从空白创建时一键填入。
 */
@Service
public class GraphTemplateService {

    private final ObjectMapper objectMapper;
    private final File templatesDir;

    public GraphTemplateService(ObjectMapper objectMapper, AppPaths appPaths) {
        this.objectMapper = objectMapper;
        // 模板存储在数据根目录下的 templates/ 子目录
        this.templatesDir = new File(appPaths.rootDir(), "templates");
        if (!templatesDir.exists()) templatesDir.mkdirs();
    }

    /** 获取所有模板列表，按 updatedAt 倒序；损坏文件静默跳过。 */
    public List<OntologyModel> list() {
        File[] files = templatesDir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return List.of();
        return Arrays.stream(files)
            .map(f -> {
                try { return objectMapper.readValue(f, OntologyModel.class); }
                catch (Exception e) { return null; }
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingLong(OntologyModel::getUpdatedAt).reversed())
            .collect(Collectors.toList());
    }

    /** 保存模板（新建或更新）。id 为空时生成 {@code tpl_<时间戳>}。 */
    public OntologyModel save(OntologyModel m) throws IOException {
        if (m.getId() == null || m.getId().isBlank()) {
            m.setId("tpl_" + System.currentTimeMillis());
        }
        long now = System.currentTimeMillis();
        if (m.getCreatedAt() == 0L) m.setCreatedAt(now);
        m.setUpdatedAt(now);
        String safe = m.getId().replaceAll("[^a-zA-Z0-9_\\-]", "_");
        JsonAtomic.write(objectMapper, new File(templatesDir, safe + ".json"), m);
        return m;
    }

    /** 删除模板；不存在时显式抛 404 让前端给出明确错误（与 OntologyModelService 风格不同：模板个数少，更需要立即反馈）。 */
    public void delete(String id) {
        String safe = id.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        File f = new File(templatesDir, safe + ".json");
        if (!f.exists()) throw new ResourceNotFoundException("Template not found: " + id);
        f.delete();
    }
}

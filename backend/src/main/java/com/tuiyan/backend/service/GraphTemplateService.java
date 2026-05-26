package com.tuiyan.backend.service;

import com.tuiyan.backend.config.ResourceNotFoundException;
import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.repository.TemplateRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 图谱模板服务：管理"图谱样板"的增删查操作。
 * <p>模板与本体模型共用 {@link OntologyModel} 数据结构，但落地到独立的 graph_template 表，
 * 列表 / 引用逻辑互不干扰。
 */
@Service
public class GraphTemplateService {

    private final TemplateRepository templateRepository;

    public GraphTemplateService(TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    /** 获取所有模板列表，按 updatedAt 倒序。 */
    public List<OntologyModel> list() {
        return templateRepository.listGraph();
    }

    /** 保存模板（新建或更新）。id 为空时生成 {@code tpl_<时间戳>}。 */
    public OntologyModel save(OntologyModel m) {
        if (m.getId() == null || m.getId().isBlank()) {
            m.setId("tpl_" + System.currentTimeMillis());
        }
        long now = System.currentTimeMillis();
        if (m.getCreatedAt() == 0L) m.setCreatedAt(now);
        m.setUpdatedAt(now);
        templateRepository.saveGraph(m);
        return m;
    }

    /** 删除模板；不存在时显式抛 404 让前端给出明确错误。 */
    public void delete(String id) {
        boolean ok = templateRepository.deleteGraph(id);
        if (!ok) throw new ResourceNotFoundException("Template not found: " + id);
    }
}

package com.tuiyan.backend.service;

import com.tuiyan.backend.model.HypothesisTemplate;
import com.tuiyan.backend.repository.TemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 推演假设模板服务：保存 / 列出 / 删除 / 标记最近使用。
 * <p>列表按 lastUsedAt 倒序，让"最近用过"的模板出现在前面。
 */
@Service
public class HypothesisTemplateService {

    private static final Logger log = LoggerFactory.getLogger(HypothesisTemplateService.class);

    private final TemplateRepository templateRepository;

    public HypothesisTemplateService(TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    /** 按 modelId 过滤列表；modelId 为空时返回全部模板。 */
    public List<HypothesisTemplate> listByModel(String modelId) {
        return templateRepository.listHypothesis(modelId);
    }

    /**
     * 保存模板（新建或更新）。
     * <p>缺失的 id / createdAt / lastUsedAt 在此自动补齐。
     */
    public HypothesisTemplate save(HypothesisTemplate t) {
        if (t.getId() == null || t.getId().isBlank()) {
            t.setId("ht_" + System.currentTimeMillis());
        }
        if (t.getCreatedAt() == 0) {
            t.setCreatedAt(System.currentTimeMillis());
        }
        t.setLastUsedAt(System.currentTimeMillis());
        templateRepository.saveHypothesis(t);
        return t;
    }

    /** 标记模板最近被使用：把 lastUsedAt 更新为当前时间；不存在时静默返回。 */
    public void touch(String id) {
        templateRepository.touchHypothesis(id);
    }

    public boolean delete(String id) {
        return templateRepository.deleteHypothesis(id);
    }
}

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

/**
 * 推演假设模板服务：保存 / 列出 / 删除 / 标记最近使用。
 * <p>模板按 modelId 过滤展示——不同图谱模型的节点 id 不通用，跨模型展示模板会让用户困惑。
 * 列表按 lastUsedAt 倒序，让"最近用过"的模板出现在前面。
 */
@Service
public class HypothesisTemplateService {

    private static final Logger log = LoggerFactory.getLogger(HypothesisTemplateService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppPaths appPaths;

    public HypothesisTemplateService(AppPaths appPaths) {
        this.appPaths = appPaths;
    }

    /**
     * 按 modelId 过滤列表；modelId 为空 / 空白时返回全部模板。
     * <p>遍历目录读所有 JSON，损坏文件跳过；按 lastUsedAt 倒序排列。
     */
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

    /**
     * 保存模板（新建或更新）。
     * <p>缺失的 id / createdAt / lastUsedAt 在此自动补齐，让 controller 层只做 IO 转发。
     * @return 保存后的模板（已带上自动补齐的字段）
     */
    public HypothesisTemplate save(HypothesisTemplate t) throws IOException {
        if (t.getId() == null || t.getId().isBlank()) {
            t.setId("ht_" + System.currentTimeMillis());
        }
        if (t.getCreatedAt() == 0) {
            t.setCreatedAt(System.currentTimeMillis());
        }
        t.setLastUsedAt(System.currentTimeMillis());
        JsonAtomic.write(objectMapper, fileFor(t.getId()), t);
        return t;
    }

    /**
     * 标记模板最近被使用：把 lastUsedAt 更新为当前时间。
     * <p>由 controller 在用户点击"使用此模板"时调用，让"最近常用"排序生效。
     * 文件不存在时静默返回，不当成错误。
     */
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

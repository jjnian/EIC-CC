package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.entity.ModelBuildSourcePO;
import com.tuiyan.backend.mapper.ModelBuildSourceMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 建图来源记录仓储：模型 ← 经验内容哈希 的对应关系。
 * <p>增量建图的两步协议：
 * <ol>
 *   <li>抽取时读 {@link #hashesOf}，跳过哈希未变化的经验；</li>
 *   <li>前端确认合并后回写 {@link #upsertAll}（结果被用户丢弃则不回写，下次仍会重抽）。</li>
 * </ol>
 */
@Repository
public class ModelBuildSourceRepository {

    private final ModelBuildSourceMapper mapper;

    public ModelBuildSourceRepository(ModelBuildSourceMapper mapper) {
        this.mapper = mapper;
    }

    /** 某模型的构建记录：experienceId → contentHash。无记录返回空 Map。 */
    public Map<String, String> hashesOf(String modelId) {
        Map<String, String> out = new HashMap<>();
        if (modelId == null || modelId.isBlank()) return out;
        List<ModelBuildSourcePO> pos = mapper.selectList(
                new LambdaQueryWrapper<ModelBuildSourcePO>().eq(ModelBuildSourcePO::getModelId, modelId));
        for (ModelBuildSourcePO po : pos) {
            out.put(po.getExperienceId(), po.getContentHash());
        }
        return out;
    }

    /**
     * 批量回写构建记录（按 experienceId 先删后插，等价 upsert；不触碰本次未涉及的记录）。
     * @param entries experienceId → contentHash
     */
    @Transactional
    public void upsertAll(String modelId, Map<String, String> entries) {
        if (modelId == null || modelId.isBlank() || entries == null || entries.isEmpty()) return;
        mapper.delete(new LambdaQueryWrapper<ModelBuildSourcePO>()
                .eq(ModelBuildSourcePO::getModelId, modelId)
                .in(ModelBuildSourcePO::getExperienceId, entries.keySet()));
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> en : entries.entrySet()) {
            ModelBuildSourcePO po = new ModelBuildSourcePO();
            po.setModelId(modelId);
            po.setExperienceId(en.getKey());
            po.setContentHash(en.getValue());
            po.setBuiltAt(now);
            mapper.insert(po);
        }
    }
}

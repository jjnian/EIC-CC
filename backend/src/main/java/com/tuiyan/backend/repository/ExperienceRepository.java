package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.entity.ExperiencePO;
import com.tuiyan.backend.mapper.ExperienceMapper;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经验库仓储：列表 / 详情 / 新增 / 编辑 / 删除，按当前工作空间隔离。
 * <p>结构与 {@link DataSourceRepository} 平行：经验库与数据源同级别，挂在工作空间下。
 */
@Repository
public class ExperienceRepository {

    private final ExperienceMapper mapper;

    public ExperienceRepository(ExperienceMapper mapper) {
        this.mapper = mapper;
    }

    /** 当前工作空间下全部经验，按创建时间倒序。 */
    public List<Map<String, Object>> list() {
        return list(WorkspaceContext.required());
    }

    /** 指定工作空间下全部经验（侧栏跨工作空间懒加载）。 */
    public List<Map<String, Object>> list(String workspaceId) {
        return mapper.selectList(new LambdaQueryWrapper<ExperiencePO>()
                        .eq(ExperiencePO::getWorkspaceId, workspaceId)
                        .orderByDesc(ExperiencePO::getCreatedAt))
                .stream().map(this::toMap).toList();
    }

    /** 跨工作空间的全量经验列表，按创建时间倒序。 */
    public List<Map<String, Object>> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<ExperiencePO>()
                        .orderByDesc(ExperiencePO::getCreatedAt))
                .stream().map(this::toMap).toList();
    }

    /** 按 id 取一行并校验工作空间归属；不归属当前 ws 返回 null。 */
    public Map<String, Object> findFull(String id) {
        ExperiencePO po = mapper.selectById(id);
        if (po == null) return null;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return null;
        return toMap(po);
    }

    @Transactional
    public Map<String, Object> create(String title, String content, String tags) {
        ExperiencePO po = new ExperiencePO();
        po.setId("exp_" + System.currentTimeMillis() + "_" + Long.toString(System.nanoTime() & 0xffff, 36));
        po.setWorkspaceId(WorkspaceContext.required());
        po.setTitle(title == null || title.isBlank() ? "未命名经验" : title.trim());
        po.setContent(content);
        po.setTags(tags);
        po.setCreatedAt(System.currentTimeMillis());
        po.setUpdatedAt(po.getCreatedAt());
        mapper.insert(po);
        return toMap(po);
    }

    @Transactional
    public Map<String, Object> update(String id, String title, String content, String tags) {
        ExperiencePO po = mapper.selectById(id);
        if (po == null) return null;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return null;
        if (title != null && !title.isBlank()) po.setTitle(title.trim());
        if (content != null) po.setContent(content);
        if (tags != null) po.setTags(tags);
        po.setUpdatedAt(System.currentTimeMillis());
        mapper.updateById(po);
        return toMap(po);
    }

    @Transactional
    public boolean delete(String id) {
        ExperiencePO po = mapper.selectById(id);
        if (po == null) return false;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return false;
        return mapper.deleteById(id) > 0;
    }

    private Map<String, Object> toMap(ExperiencePO po) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", po.getId());
        out.put("workspaceId", po.getWorkspaceId());
        out.put("title", po.getTitle());
        if (po.getContent() != null) out.put("content", po.getContent());
        if (po.getTags() != null) out.put("tags", po.getTags());
        out.put("createdAt", po.getCreatedAt());
        if (po.getUpdatedAt() != null) out.put("updatedAt", po.getUpdatedAt());
        return out;
    }
}

package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.entity.ExperienceFolderPO;
import com.tuiyan.backend.mapper.ExperienceFolderMapper;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经验库文件夹仓储：工作空间内任意层级（parent_id 自引用）的增删改查，按工作空间隔离。
 * <p>结构与 {@link DataSourceFolderRepository} 平行。移动文件夹时做环检测（不能移到自身或
 * 自己的子孙下）；删除文件夹时把其直接子文件夹与直接经验「上提」到该文件夹的父级，避免误删数据。
 */
@Repository
public class ExperienceFolderRepository {

    private final ExperienceFolderMapper mapper;
    private final ExperienceRefRepository refRepo;

    public ExperienceFolderRepository(ExperienceFolderMapper mapper, ExperienceRefRepository refRepo) {
        this.mapper = mapper;
        this.refRepo = refRepo;
    }

    /** 指定工作空间下全部文件夹（前端自行拼成树）。 */
    public List<Map<String, Object>> listMaps(String workspaceId) {
        return mapper.selectList(new LambdaQueryWrapper<ExperienceFolderPO>()
                        .eq(ExperienceFolderPO::getWorkspaceId, workspaceId)
                        .orderByAsc(ExperienceFolderPO::getSortOrder)
                        .orderByAsc(ExperienceFolderPO::getCreatedAt))
                .stream().map(ExperienceFolderRepository::toMap).toList();
    }

    @Transactional
    public ExperienceFolderPO create(String name, String parentId) {
        String ws = WorkspaceContext.required();
        String parent = blankToNull(parentId);
        if (parent != null) requireSameWsFolder(parent, ws); // 父文件夹必须存在且同工作空间
        ExperienceFolderPO po = new ExperienceFolderPO();
        po.setId("expf_" + System.currentTimeMillis() + "_" + Long.toString(System.nanoTime() & 0xffff, 36));
        po.setWorkspaceId(ws);
        po.setParentId(parent);
        po.setName(name == null || name.isBlank() ? "新文件夹" : name.trim());
        po.setSortOrder(0);
        long now = System.currentTimeMillis();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        mapper.insert(po);
        return po;
    }

    /**
     * 重命名 / 移动文件夹。
     * @param newName     非空则重命名
     * @param moveParent  是否要改父级（请求体里出现 parentId 字段即视为移动）
     * @param newParentId 新父文件夹 id（null/空 = 移到根）
     */
    @Transactional
    public boolean update(String id, String newName, boolean moveParent, String newParentId) {
        String ws = WorkspaceContext.required();
        ExperienceFolderPO po = mapper.selectById(id);
        if (po == null || !ws.equals(po.getWorkspaceId())) return false;
        if (newName != null && !newName.isBlank()) po.setName(newName.trim());
        if (moveParent) {
            String np = blankToNull(newParentId);
            if (id.equals(np)) throw new IllegalArgumentException("不能把文件夹移动到自身");
            if (np != null) {
                requireSameWsFolder(np, ws);
                if (isDescendantOf(np, id, ws)) {
                    throw new IllegalArgumentException("不能把文件夹移动到它自己的子目录下");
                }
            }
            po.setParentId(np);
        }
        po.setUpdatedAt(System.currentTimeMillis());
        return mapper.updateById(po) > 0;
    }

    /** 删除文件夹：直接子文件夹与直接经验上提到本文件夹的父级，然后删除本文件夹。 */
    @Transactional
    public boolean delete(String id) {
        String ws = WorkspaceContext.required();
        ExperienceFolderPO po = mapper.selectById(id);
        if (po == null || !ws.equals(po.getWorkspaceId())) return false;
        String parentId = po.getParentId();
        long now = System.currentTimeMillis();

        // 1) 子文件夹上提
        List<ExperienceFolderPO> children = mapper.selectList(new LambdaQueryWrapper<ExperienceFolderPO>()
                .eq(ExperienceFolderPO::getWorkspaceId, ws)
                .eq(ExperienceFolderPO::getParentId, id));
        for (ExperienceFolderPO child : children) {
            child.setParentId(parentId);
            child.setUpdatedAt(now);
            mapper.updateById(child);
        }
        // 2) 本工作空间内归在本文件夹下的「经验引用」上提到父级（作用于引用而非经验本体）
        refRepo.reparentFolder(ws, id, parentId);
        return mapper.deleteById(id) > 0;
    }

    /** 校验 folderId 存在且属于当前工作空间，否则抛 400。 */
    private void requireSameWsFolder(String folderId, String ws) {
        ExperienceFolderPO f = mapper.selectById(folderId);
        if (f == null || !ws.equals(f.getWorkspaceId())) {
            throw new IllegalArgumentException("目标文件夹不存在或不属于当前工作空间");
        }
    }

    /** node 是否在 ancestor 的子树内（从 node 向上回溯遇到 ancestor 即是）——用于移动时的环检测。 */
    private boolean isDescendantOf(String nodeId, String ancestorId, String ws) {
        String cur = nodeId;
        int guard = 0;
        while (cur != null && guard++ < 1000) {
            if (cur.equals(ancestorId)) return true;
            ExperienceFolderPO p = mapper.selectById(cur);
            if (p == null || !ws.equals(p.getWorkspaceId())) break;
            cur = p.getParentId();
        }
        return false;
    }

    public static Map<String, Object> toMap(ExperienceFolderPO po) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", po.getId());
        out.put("name", po.getName());
        if (po.getParentId() != null) out.put("parentId", po.getParentId());
        if (po.getSortOrder() != null) out.put("sortOrder", po.getSortOrder());
        out.put("createdAt", po.getCreatedAt());
        if (po.getUpdatedAt() != null) out.put("updatedAt", po.getUpdatedAt());
        return out;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}

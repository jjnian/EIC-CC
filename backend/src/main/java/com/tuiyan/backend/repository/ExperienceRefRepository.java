package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.entity.ExperienceRefPO;
import com.tuiyan.backend.mapper.ExperienceRefMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经验库引用仓储：工作空间 ←→ 经验 的多对多引用关系（experience_ref 表）。
 * <p>经验库为全局公共资源，新增不再自动归属工作空间；工作空间通过「引用」把公共经验纳入侧栏树。
 * 一条引用记录该工作空间内的归类文件夹（{@code folderId}），各工作空间各自归类、互不影响。
 */
@Repository
public class ExperienceRefRepository {

    private final ExperienceRefMapper mapper;

    public ExperienceRefRepository(ExperienceRefMapper mapper) {
        this.mapper = mapper;
    }

    /** 某工作空间引用的全部经验记录（含 folderId）。 */
    public List<ExperienceRefPO> listByWorkspace(String workspaceId) {
        return mapper.selectList(new LambdaQueryWrapper<ExperienceRefPO>()
                .eq(ExperienceRefPO::getWorkspaceId, workspaceId));
    }

    /** 某工作空间引用的经验 id → 其在该工作空间内的归类文件夹 id（folderId 可为 null）。 */
    public Map<String, String> folderByExperience(String workspaceId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ExperienceRefPO r : listByWorkspace(workspaceId)) {
            out.put(r.getExperienceId(), r.getFolderId());
        }
        return out;
    }

    private ExperienceRefPO find(String workspaceId, String experienceId) {
        return mapper.selectOne(new LambdaQueryWrapper<ExperienceRefPO>()
                .eq(ExperienceRefPO::getWorkspaceId, workspaceId)
                .eq(ExperienceRefPO::getExperienceId, experienceId)
                .last("LIMIT 1"));
    }

    /** 把一批经验引用进当前工作空间（已引用的跳过），返回新增条数。 */
    @Transactional
    public int reference(String workspaceId, List<String> experienceIds, String folderId) {
        if (experienceIds == null) return 0;
        int added = 0;
        for (String expId : experienceIds) {
            if (expId == null || expId.isBlank()) continue;
            if (find(workspaceId, expId) != null) continue;
            ExperienceRefPO po = new ExperienceRefPO();
            po.setId("expref_" + System.currentTimeMillis() + "_"
                    + Long.toString(System.nanoTime() & 0xffffff, 36));
            po.setWorkspaceId(workspaceId);
            po.setExperienceId(expId.trim());
            po.setFolderId(folderId == null || folderId.isBlank() ? null : folderId.trim());
            po.setCreatedAt(System.currentTimeMillis());
            mapper.insert(po);
            added++;
        }
        return added;
    }

    /** 取消某工作空间对某经验的引用（不删除经验本体）。 */
    @Transactional
    public boolean unreference(String workspaceId, String experienceId) {
        return mapper.delete(new LambdaQueryWrapper<ExperienceRefPO>()
                .eq(ExperienceRefPO::getWorkspaceId, workspaceId)
                .eq(ExperienceRefPO::getExperienceId, experienceId)) > 0;
    }

    /** 修改某经验在某工作空间内的归类文件夹（folderId=null 移到根）。 */
    @Transactional
    public boolean setFolder(String workspaceId, String experienceId, String folderId) {
        ExperienceRefPO po = find(workspaceId, experienceId);
        if (po == null) return false;
        po.setFolderId(folderId == null || folderId.isBlank() ? null : folderId.trim());
        return mapper.updateById(po) > 0;
    }

    /** 删除文件夹时把该工作空间内归在 fromFolderId 下的引用上提到 toFolderId（可为 null=根）。 */
    @Transactional
    public void reparentFolder(String workspaceId, String fromFolderId, String toFolderId) {
        List<ExperienceRefPO> rows = mapper.selectList(new LambdaQueryWrapper<ExperienceRefPO>()
                .eq(ExperienceRefPO::getWorkspaceId, workspaceId)
                .eq(ExperienceRefPO::getFolderId, fromFolderId));
        for (ExperienceRefPO po : rows) {
            po.setFolderId(toFolderId);
            mapper.updateById(po);
        }
    }

    /** 经验本体被删除时清理其全部引用（跨工作空间）。 */
    @Transactional
    public void deleteByExperience(String experienceId) {
        mapper.delete(new LambdaQueryWrapper<ExperienceRefPO>()
                .eq(ExperienceRefPO::getExperienceId, experienceId));
    }

    /** 工作空间被删除时清理它的全部经验引用（经验本体作为公共资源保留）。 */
    @Transactional
    public void deleteByWorkspace(String workspaceId) {
        mapper.delete(new LambdaQueryWrapper<ExperienceRefPO>()
                .eq(ExperienceRefPO::getWorkspaceId, workspaceId));
    }
}

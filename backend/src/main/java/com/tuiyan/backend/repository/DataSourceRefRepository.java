package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.entity.DataSourceRefPO;
import com.tuiyan.backend.mapper.DataSourceRefMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源引用仓储：工作空间 ←→ 数据源 的多对多引用关系（data_source_ref 表）。
 * <p>数据源为全局公共资源，新增不再自动归属工作空间；工作空间通过「引用」把公共数据源纳入侧栏树。
 * 一条引用记录该工作空间内的归类文件夹（{@code folderId}），各工作空间各自归类、互不影响。
 */
@Repository
public class DataSourceRefRepository {

    private final DataSourceRefMapper mapper;

    public DataSourceRefRepository(DataSourceRefMapper mapper) {
        this.mapper = mapper;
    }

    /** 某工作空间引用的全部数据源记录（含 folderId）。 */
    public List<DataSourceRefPO> listByWorkspace(String workspaceId) {
        return mapper.selectList(new LambdaQueryWrapper<DataSourceRefPO>()
                .eq(DataSourceRefPO::getWorkspaceId, workspaceId));
    }

    /** 某工作空间引用的数据源 id → 其在该工作空间内的归类文件夹 id（folderId 可为 null）。 */
    public Map<String, String> folderByDataSource(String workspaceId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (DataSourceRefPO r : listByWorkspace(workspaceId)) {
            out.put(r.getDataSourceId(), r.getFolderId());
        }
        return out;
    }

    private DataSourceRefPO find(String workspaceId, String dataSourceId) {
        return mapper.selectOne(new LambdaQueryWrapper<DataSourceRefPO>()
                .eq(DataSourceRefPO::getWorkspaceId, workspaceId)
                .eq(DataSourceRefPO::getDataSourceId, dataSourceId)
                .last("LIMIT 1"));
    }

    /** 把一批数据源引用进当前工作空间（已引用的跳过），返回新增条数。 */
    @Transactional
    public int reference(String workspaceId, List<String> dataSourceIds, String folderId) {
        if (dataSourceIds == null) return 0;
        int added = 0;
        for (String dsId : dataSourceIds) {
            if (dsId == null || dsId.isBlank()) continue;
            if (find(workspaceId, dsId) != null) continue;
            DataSourceRefPO po = new DataSourceRefPO();
            po.setId("dsref_" + System.currentTimeMillis() + "_"
                    + Long.toString(System.nanoTime() & 0xffffff, 36));
            po.setWorkspaceId(workspaceId);
            po.setDataSourceId(dsId.trim());
            po.setFolderId(folderId == null || folderId.isBlank() ? null : folderId.trim());
            po.setCreatedAt(System.currentTimeMillis());
            mapper.insert(po);
            added++;
        }
        return added;
    }

    /** 取消某工作空间对某数据源的引用（不删除数据源本体）。 */
    @Transactional
    public boolean unreference(String workspaceId, String dataSourceId) {
        return mapper.delete(new LambdaQueryWrapper<DataSourceRefPO>()
                .eq(DataSourceRefPO::getWorkspaceId, workspaceId)
                .eq(DataSourceRefPO::getDataSourceId, dataSourceId)) > 0;
    }

    /** 修改某数据源在某工作空间内的归类文件夹（folderId=null 移到根）。 */
    @Transactional
    public boolean setFolder(String workspaceId, String dataSourceId, String folderId) {
        DataSourceRefPO po = find(workspaceId, dataSourceId);
        if (po == null) return false;
        po.setFolderId(folderId == null || folderId.isBlank() ? null : folderId.trim());
        return mapper.updateById(po) > 0;
    }

    /** 删除文件夹时把该工作空间内归在 fromFolderId 下的引用上提到 toFolderId（可为 null=根）。 */
    @Transactional
    public void reparentFolder(String workspaceId, String fromFolderId, String toFolderId) {
        List<DataSourceRefPO> rows = mapper.selectList(new LambdaQueryWrapper<DataSourceRefPO>()
                .eq(DataSourceRefPO::getWorkspaceId, workspaceId)
                .eq(DataSourceRefPO::getFolderId, fromFolderId));
        for (DataSourceRefPO po : rows) {
            po.setFolderId(toFolderId);
            mapper.updateById(po);
        }
    }

    /** 数据源本体被删除时清理其全部引用（跨工作空间）。 */
    @Transactional
    public void deleteByDataSource(String dataSourceId) {
        mapper.delete(new LambdaQueryWrapper<DataSourceRefPO>()
                .eq(DataSourceRefPO::getDataSourceId, dataSourceId));
    }

    /** 工作空间被删除时清理它的全部数据源引用（数据源本体作为公共资源保留）。 */
    @Transactional
    public void deleteByWorkspace(String workspaceId) {
        mapper.delete(new LambdaQueryWrapper<DataSourceRefPO>()
                .eq(DataSourceRefPO::getWorkspaceId, workspaceId));
    }
}

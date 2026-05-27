package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.entity.WorkspacePO;
import com.tuiyan.backend.mapper.WorkspaceMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 工作空间仓储：CRUD + 默认 ws 保障。
 * <p>workspace 表本身不带 workspace_id 维度，所以查询不走 WorkspaceContext 过滤。
 */
@Repository
public class WorkspaceRepository {

    public static final String DEFAULT_ID = "ws_default";

    private final WorkspaceMapper mapper;

    public WorkspaceRepository(WorkspaceMapper mapper) {
        this.mapper = mapper;
    }

    public List<WorkspacePO> list() {
        return mapper.selectList(
                new LambdaQueryWrapper<WorkspacePO>()
                        .orderByAsc(WorkspacePO::getSortNo)
                        .orderByAsc(WorkspacePO::getCreatedAt));
    }

    public WorkspacePO get(String id) {
        return mapper.selectById(id);
    }

    public boolean exists(String id) {
        return id != null && !id.isBlank() && mapper.selectById(id) != null;
    }

    @Transactional
    public WorkspacePO create(WorkspacePO ws) {
        long now = System.currentTimeMillis();
        if (ws.getId() == null || ws.getId().isBlank()) {
            ws.setId("ws_" + now);
        }
        if (ws.getCreatedAt() == null) ws.setCreatedAt(now);
        ws.setUpdatedAt(now);
        if (ws.getIsDefault() == null) ws.setIsDefault(false);
        if (ws.getSortNo() == null) ws.setSortNo(0);
        mapper.insert(ws);
        return ws;
    }

    @Transactional
    public WorkspacePO update(WorkspacePO ws) {
        ws.setUpdatedAt(System.currentTimeMillis());
        mapper.updateById(ws);
        return mapper.selectById(ws.getId());
    }

    @Transactional
    public boolean delete(String id) {
        return mapper.deleteById(id) > 0;
    }
}

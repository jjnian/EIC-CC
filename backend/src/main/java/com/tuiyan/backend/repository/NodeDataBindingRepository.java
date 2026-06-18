package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.entity.NodeDataBindingPO;
import com.tuiyan.backend.mapper.NodeDataBindingMapper;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 节点数据供血绑定仓储：按工作空间隔离，支持按模型 / 节点过滤。
 */
@Repository
public class NodeDataBindingRepository {

    private final NodeDataBindingMapper mapper;

    public NodeDataBindingRepository(NodeDataBindingMapper mapper) {
        this.mapper = mapper;
    }

    /** 列出某模型（可选某节点）下的绑定，按创建时间倒序。 */
    public List<Map<String, Object>> list(String modelId, String nodeId) {
        LambdaQueryWrapper<NodeDataBindingPO> q = new LambdaQueryWrapper<NodeDataBindingPO>()
                .eq(NodeDataBindingPO::getWorkspaceId, WorkspaceContext.required())
                .eq(NodeDataBindingPO::getModelId, modelId)
                .orderByDesc(NodeDataBindingPO::getCreatedAt);
        if (nodeId != null && !nodeId.isBlank()) q.eq(NodeDataBindingPO::getNodeId, nodeId);
        return mapper.selectList(q).stream().map(this::toMap).toList();
    }

    /**
     * 全量绑定中「数据源 → 引用它的工作空间 id 列表」的映射（跨工作空间，不做隔离）。
     * 供「公共数据源」总览页展示每个数据源被哪些工作空间通过节点供血绑定引用。
     */
    public Map<String, List<String>> referencingWorkspacesByDataSource() {
        List<NodeDataBindingPO> all = mapper.selectList(new LambdaQueryWrapper<NodeDataBindingPO>()
                .select(NodeDataBindingPO::getDataSourceId, NodeDataBindingPO::getWorkspaceId));
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (NodeDataBindingPO po : all) {
            if (po.getDataSourceId() == null || po.getWorkspaceId() == null) continue;
            grouped.computeIfAbsent(po.getDataSourceId(), k -> new LinkedHashSet<>()).add(po.getWorkspaceId());
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        grouped.forEach((dsId, wsIds) -> out.put(dsId, new ArrayList<>(wsIds)));
        return out;
    }

    /** 按 id 取一行并校验工作空间归属；不归属当前 ws 返回 null。 */
    public NodeDataBindingPO findScoped(String id) {
        NodeDataBindingPO po = mapper.selectById(id);
        if (po == null) return null;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return null;
        return po;
    }

    @Transactional
    public Map<String, Object> create(String modelId, String nodeId, String dataSourceId,
                                      String tableName, String columnMap, String filterSql) {
        NodeDataBindingPO po = new NodeDataBindingPO();
        po.setId("ndb_" + System.currentTimeMillis() + "_" + Long.toString(System.nanoTime() & 0xffff, 36));
        po.setWorkspaceId(WorkspaceContext.required());
        po.setModelId(modelId);
        po.setNodeId(nodeId);
        po.setDataSourceId(dataSourceId);
        po.setTableName(tableName);
        po.setColumnMap(columnMap);
        po.setFilterSql(filterSql);
        po.setCreatedAt(System.currentTimeMillis());
        po.setUpdatedAt(po.getCreatedAt());
        mapper.insert(po);
        return toMap(po);
    }

    @Transactional
    public Map<String, Object> update(String id, String dataSourceId, String tableName,
                                      String columnMap, String filterSql) {
        NodeDataBindingPO po = findScoped(id);
        if (po == null) return null;
        if (dataSourceId != null && !dataSourceId.isBlank()) po.setDataSourceId(dataSourceId);
        if (tableName != null) po.setTableName(tableName);
        po.setColumnMap(columnMap);
        po.setFilterSql(filterSql);
        po.setUpdatedAt(System.currentTimeMillis());
        mapper.updateById(po);
        return toMap(po);
    }

    @Transactional
    public boolean delete(String id) {
        NodeDataBindingPO po = findScoped(id);
        if (po == null) return false;
        return mapper.deleteById(id) > 0;
    }

    private Map<String, Object> toMap(NodeDataBindingPO po) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", po.getId());
        out.put("workspaceId", po.getWorkspaceId());
        out.put("modelId", po.getModelId());
        out.put("nodeId", po.getNodeId());
        out.put("dataSourceId", po.getDataSourceId());
        if (po.getTableName() != null) out.put("tableName", po.getTableName());
        if (po.getColumnMap() != null) out.put("columnMap", po.getColumnMap());
        if (po.getFilterSql() != null) out.put("filterSql", po.getFilterSql());
        out.put("createdAt", po.getCreatedAt());
        if (po.getUpdatedAt() != null) out.put("updatedAt", po.getUpdatedAt());
        return out;
    }
}

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

    /**
     * 按 id 取一行，<b>不做工作空间校验</b>——仅供态势调度器等无请求上下文的后台线程使用
     * （与 HttpScheduler 直查数据源同一模式），用户可达的端点一律走 {@link #findScoped}。
     */
    public NodeDataBindingPO findAnyById(String id) {
        return mapper.selectById(id);
    }

    /** 全部启用了状态刷新的绑定（跨工作空间）：态势调度器启动时批量注册。 */
    public List<NodeDataBindingPO> listStatusEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<NodeDataBindingPO>()
                .eq(NodeDataBindingPO::getStatusEnabled, Boolean.TRUE));
    }

    /** 更新态势层状态源配置（含归属校验）；不存在/不归属返回 null。 */
    @Transactional
    public Map<String, Object> updateStatusConfig(String id, String statusQuery, String statusRulesJson,
                                                  Boolean statusEnabled, Integer statusIntervalSec) {
        NodeDataBindingPO po = findScoped(id);
        if (po == null) return null;
        po.setStatusQuery(statusQuery);
        po.setStatusRulesJson(statusRulesJson);
        po.setStatusEnabled(statusEnabled != null && statusEnabled);
        po.setStatusIntervalSec(statusIntervalSec);
        po.setUpdatedAt(System.currentTimeMillis());
        mapper.updateById(po);
        return toMap(po);
    }

    /** 后台线程停用某绑定的状态刷新（连续失败自动停用用，无工作空间上下文）。 */
    @Transactional
    public void disableStatusAny(String id) {
        NodeDataBindingPO po = mapper.selectById(id);
        if (po == null) return;
        po.setStatusEnabled(Boolean.FALSE);
        mapper.updateById(po);
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

    /** 数据源本体被删除时清理其全部供血绑定（跨工作空间），避免绑定悬挂指向已不存在的数据源。 */
    @Transactional
    public void deleteByDataSource(String dataSourceId) {
        mapper.delete(new LambdaQueryWrapper<NodeDataBindingPO>()
                .eq(NodeDataBindingPO::getDataSourceId, dataSourceId));
    }

    /**
     * 本体模型被删除时清理其全部供血绑定：node_data_binding.model_id 没有 DB 级外键约束
     * （不像 ontology_node/edge 那样靠 ON DELETE CASCADE），删模型时若不主动清理，
     * 绑定会悬挂指向已不存在的 model_id，运行时取数供血才报错。
     */
    @Transactional
    public void deleteByModel(String modelId) {
        mapper.delete(new LambdaQueryWrapper<NodeDataBindingPO>()
                .eq(NodeDataBindingPO::getModelId, modelId));
    }

    /** 工作空间被删除时清理它名下的全部供血绑定(该工作空间下的模型是被批量 delete 的,不会逐个走 deleteByModel)。 */
    @Transactional
    public void deleteByWorkspace(String workspaceId) {
        mapper.delete(new LambdaQueryWrapper<NodeDataBindingPO>()
                .eq(NodeDataBindingPO::getWorkspaceId, workspaceId));
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
        if (po.getStatusQuery() != null) out.put("statusQuery", po.getStatusQuery());
        if (po.getStatusRulesJson() != null) out.put("statusRules", po.getStatusRulesJson());
        out.put("statusEnabled", Boolean.TRUE.equals(po.getStatusEnabled()));
        if (po.getStatusIntervalSec() != null) out.put("statusIntervalSec", po.getStatusIntervalSec());
        out.put("createdAt", po.getCreatedAt());
        if (po.getUpdatedAt() != null) out.put("updatedAt", po.getUpdatedAt());
        return out;
    }
}

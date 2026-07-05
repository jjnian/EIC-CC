package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.OntologyEdgePO;
import com.tuiyan.backend.entity.OntologyModelPO;
import com.tuiyan.backend.entity.OntologyNodePO;
import com.tuiyan.backend.entity.OntologyNodePropPO;
import com.tuiyan.backend.mapper.OntologyEdgeMapper;
import com.tuiyan.backend.mapper.OntologyModelMapper;
import com.tuiyan.backend.mapper.OntologyNodeMapper;
import com.tuiyan.backend.mapper.OntologyNodePropMapper;
import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本体模型聚合根仓储：负责 OntologyModel 与底层 4 张表（model + node + prop + edge）之间的读写编排。
 * <p>save 走"先删后插"的覆盖式策略，实现简单且与现有"全量保存"语义一致。
 * 业务方法签名与原 OntologyModelService 兼容，保持 controller 层无感知。
 * <p>node/edge/props 的逐行 PO↔Map 映射、props 拆行与 N+1 规避读取委派给 {@link OntologyRowMapper}；
 * 本类只保留聚合编排、workspace 校验、级联删除与 {@code @Transactional} 事务边界。
 */
@Repository
public class OntologyModelRepository {

    private final OntologyModelMapper modelMapper;
    private final OntologyNodeMapper nodeMapper;
    private final OntologyNodePropMapper propMapper;
    private final OntologyEdgeMapper edgeMapper;
    private final OntologyRowMapper rowMapper;
    private final NodeDataBindingRepository bindingRepo;
    private final JsonCodec codec;

    public OntologyModelRepository(OntologyModelMapper modelMapper,
                                   OntologyNodeMapper nodeMapper,
                                   OntologyNodePropMapper propMapper,
                                   OntologyEdgeMapper edgeMapper,
                                   OntologyRowMapper rowMapper,
                                   NodeDataBindingRepository bindingRepo,
                                   ObjectMapper objectMapper) {
        this.modelMapper = modelMapper;
        this.nodeMapper = nodeMapper;
        this.propMapper = propMapper;
        this.edgeMapper = edgeMapper;
        this.rowMapper = rowMapper;
        this.bindingRepo = bindingRepo;
        this.codec = new JsonCodec(objectMapper);
    }

    /** 计数；启动时种子判定用。 */
    public long count() {
        return modelMapper.selectCount(
                new LambdaQueryWrapper<OntologyModelPO>().eq(OntologyModelPO::getWorkspaceId, WorkspaceContext.required()));
    }

    /** 列表，按 updated_at 倒序。 */
    public List<OntologyModel> list() {
        List<OntologyModelPO> pos = modelMapper.selectList(
                new LambdaQueryWrapper<OntologyModelPO>()
                        .eq(OntologyModelPO::getWorkspaceId, WorkspaceContext.required())
                        .orderByDesc(OntologyModelPO::getUpdatedAt));
        List<OntologyModel> out = new ArrayList<>(pos.size());
        for (OntologyModelPO po : pos) {
            out.add(loadModel(po));
        }
        return out;
    }

    /** 按 id 加载完整模型（含节点、props、边）；不存在或不属于当前 ws 返回 null。 */
    public OntologyModel get(String id) {
        OntologyModelPO po = modelMapper.selectById(id);
        if (po == null) return null;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return null;
        return loadModel(po);
    }

    /**
     * 全量保存（覆盖）。
     * <p>事务内：upsert 模型主行 → 删旧节点/props/边 → 插新节点/props/边。
     */
    @Transactional
    public void save(OntologyModel m) {
        // 1. upsert 主表
        OntologyModelPO po = toPO(m);
        OntologyModelPO existing = modelMapper.selectById(m.getId());
        if (existing == null) {
            po.setWorkspaceId(WorkspaceContext.required());
            modelMapper.insert(po);
        } else {
            if (!WorkspaceContext.required().equals(existing.getWorkspaceId())) {
                throw new IllegalArgumentException("Model does not belong to current workspace: " + m.getId());
            }
            po.setWorkspaceId(existing.getWorkspaceId());
            modelMapper.updateById(po);
        }

        // 2. 删除旧的节点 / props / 边（外键级联会清 props，但显式删除更直观）
        deleteAllChildren(m.getId());

        // 3. 插入新的节点
        if (m.getGraphData() != null) {
            List<Map<String, Object>> nodes = m.getGraphData().getNodes();
            if (nodes != null) {
                for (Map<String, Object> n : nodes) {
                    rowMapper.insertNode(m.getId(), n);
                }
            }
            List<Map<String, Object>> edges = m.getGraphData().getEdges();
            if (edges != null) {
                for (Map<String, Object> e : edges) {
                    rowMapper.insertEdge(m.getId(), e);
                }
            }
        }
    }

    /** 物理删除模型；外键级联自动清理子表。仅允许删除当前 workspace 下的模型。 */
    @Transactional
    public boolean delete(String id) {
        OntologyModelPO po = modelMapper.selectById(id);
        if (po == null) return false;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return false;
        boolean removed = modelMapper.deleteById(id) > 0;
        if (removed) bindingRepo.deleteByModel(id);
        return removed;
    }

    /** 删除指定模型的所有节点/props/边（用于覆盖保存时的清理）。 */
    @Transactional
    public void deleteAllChildren(String modelId) {
        propMapper.delete(new LambdaQueryWrapper<OntologyNodePropPO>()
                .eq(OntologyNodePropPO::getModelId, modelId));
        nodeMapper.delete(new LambdaQueryWrapper<OntologyNodePO>()
                .eq(OntologyNodePO::getModelId, modelId));
        edgeMapper.delete(new LambdaQueryWrapper<OntologyEdgePO>()
                .eq(OntologyEdgePO::getModelId, modelId));
    }

    // ---------- 外科手术式局部编辑（大图快速修复：只动指定行，不加载/不重存整图） ----------

    /** 局部 upsert 单个节点（先删旧行+props 再插）。 */
    @Transactional
    public void patchUpsertNode(String modelId, Map<String, Object> node) {
        String id = str(node.get("id"));
        if (id.isEmpty()) throw new IllegalArgumentException("节点缺少 id");
        propMapper.delete(new LambdaQueryWrapper<OntologyNodePropPO>()
                .eq(OntologyNodePropPO::getModelId, modelId).eq(OntologyNodePropPO::getNodeId, id));
        nodeMapper.delete(new LambdaQueryWrapper<OntologyNodePO>()
                .eq(OntologyNodePO::getModelId, modelId).eq(OntologyNodePO::getId, id));
        rowMapper.insertNode(modelId, node);
    }

    /** 局部删除单个节点：连带其 props 与所有关联边（from/to 指向它）。返回删除的节点行数。 */
    @Transactional
    public int patchDeleteNode(String modelId, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return 0;
        propMapper.delete(new LambdaQueryWrapper<OntologyNodePropPO>()
                .eq(OntologyNodePropPO::getModelId, modelId).eq(OntologyNodePropPO::getNodeId, nodeId));
        edgeMapper.delete(new LambdaQueryWrapper<OntologyEdgePO>()
                .eq(OntologyEdgePO::getModelId, modelId)
                .and(w -> w.eq(OntologyEdgePO::getFromNodeId, nodeId).or().eq(OntologyEdgePO::getToNodeId, nodeId)));
        return nodeMapper.delete(new LambdaQueryWrapper<OntologyNodePO>()
                .eq(OntologyNodePO::getModelId, modelId).eq(OntologyNodePO::getId, nodeId));
    }

    /** 局部 upsert 单条边（先删旧行再插）。 */
    @Transactional
    public void patchUpsertEdge(String modelId, Map<String, Object> edge) {
        String id = str(edge.get("id"));
        if (id.isEmpty()) throw new IllegalArgumentException("边缺少 id");
        edgeMapper.delete(new LambdaQueryWrapper<OntologyEdgePO>()
                .eq(OntologyEdgePO::getModelId, modelId).eq(OntologyEdgePO::getId, id));
        rowMapper.insertEdge(modelId, edge);
    }

    /** 局部删除单条边。返回删除行数。 */
    @Transactional
    public int patchDeleteEdge(String modelId, String edgeId) {
        if (edgeId == null || edgeId.isBlank()) return 0;
        return edgeMapper.delete(new LambdaQueryWrapper<OntologyEdgePO>()
                .eq(OntologyEdgePO::getModelId, modelId).eq(OntologyEdgePO::getId, edgeId));
    }

    /** 刷新模型 updatedAt（局部编辑后调用一次）。 */
    @Transactional
    public void touch(String modelId) {
        OntologyModelPO po = modelMapper.selectById(modelId);
        if (po == null) return;
        po.setUpdatedAt(System.currentTimeMillis());
        po.setUpdatedLabel("刚刚");
        modelMapper.updateById(po);
    }

    /** 当前节点数（不加载整图）。 */
    public long nodeCountOf(String modelId) {
        return nodeMapper.selectCount(new LambdaQueryWrapper<OntologyNodePO>()
                .eq(OntologyNodePO::getModelId, modelId));
    }

    /** 当前边数（不加载整图）。 */
    public long edgeCountOf(String modelId) {
        return edgeMapper.selectCount(new LambdaQueryWrapper<OntologyEdgePO>()
                .eq(OntologyEdgePO::getModelId, modelId));
    }

    /** 按 id 集取节点头(id/label/type/domain)，不加载整图——供对话改图用「当前可见子图」作上下文。 */
    public List<Map<String, Object>> nodeHeadsByIds(String modelId, java.util.Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return nodeMapper.selectList(new LambdaQueryWrapper<OntologyNodePO>()
                        .eq(OntologyNodePO::getModelId, modelId).in(OntologyNodePO::getId, ids))
                .stream().map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("label", p.getLabel());
                    m.put("type", p.getType());
                    m.put("domain", p.getDomain());
                    return m;
                }).toList();
    }

    /** 取任一端在 id 集内的边(id/from/to/rel_type/label)，用于取相关节点的一跳邻居；不加载整图。 */
    public List<Map<String, Object>> edgesIncident(String modelId, java.util.Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return edgeMapper.selectList(new LambdaQueryWrapper<OntologyEdgePO>()
                        .eq(OntologyEdgePO::getModelId, modelId)
                        .and(w -> w.in(OntologyEdgePO::getFromNodeId, ids).or().in(OntologyEdgePO::getToNodeId, ids)))
                .stream().map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("from", p.getFromNodeId());
                    m.put("to", p.getToNodeId());
                    m.put("rel_type", p.getRelType());
                    m.put("label", p.getLabel());
                    return m;
                }).toList();
    }

    /** 取两端都在 id 集内的边(id/from/to/rel_type/label)，不加载整图。 */
    public List<Map<String, Object>> edgesAmongIds(String modelId, java.util.Collection<String> ids) {
        if (ids == null || ids.size() < 2) return List.of();
        return edgeMapper.selectList(new LambdaQueryWrapper<OntologyEdgePO>()
                        .eq(OntologyEdgePO::getModelId, modelId)
                        .in(OntologyEdgePO::getFromNodeId, ids).in(OntologyEdgePO::getToNodeId, ids))
                .stream().map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("from", p.getFromNodeId());
                    m.put("to", p.getToNodeId());
                    m.put("rel_type", p.getRelType());
                    m.put("label", p.getLabel());
                    return m;
                }).toList();
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    // ---------- 内部编排 ----------

    private OntologyModelPO toPO(OntologyModel m) {
        OntologyModelPO po = new OntologyModelPO();
        po.setId(m.getId());
        po.setTitle(m.getTitle());
        po.setDescription(m.getDesc());
        po.setUpdatedLabel(m.getUpdated());
        po.setCreatedAt(m.getCreatedAt());
        po.setUpdatedAt(m.getUpdatedAt());
        return po;
    }

    private OntologyModel loadModel(OntologyModelPO po) {
        OntologyModel m = new OntologyModel();
        m.setId(po.getId());
        m.setTitle(po.getTitle());
        m.setDesc(po.getDescription());
        m.setUpdated(po.getUpdatedLabel());
        m.setCreatedAt(po.getCreatedAt() == null ? 0L : po.getCreatedAt());
        m.setUpdatedAt(po.getUpdatedAt() == null ? 0L : po.getUpdatedAt());

        OntologyModel.GraphData g = new OntologyModel.GraphData();
        g.setNodes(rowMapper.loadNodes(po.getId()));
        g.setEdges(rowMapper.loadEdges(po.getId()));
        m.setGraphData(g);
        return m;
    }

    /** 轻量归属校验：模型存在且属于当前工作空间（只查主行，不加载整图；供大图查询接口防 IDOR）。 */
    public boolean existsInWorkspace(String id) {
        OntologyModelPO po = modelMapper.selectById(id);
        return po != null && WorkspaceContext.required().equals(po.getWorkspaceId());
    }

    /** 工具：取 model 子节点的工具方法供 OntologyVersionRepository 共用（按 modelId 拉节点 + props + 边）。 */
    public NodesAndEdges loadGraphForVersion(String modelId) {
        return new NodesAndEdges(rowMapper.loadNodes(modelId), rowMapper.loadEdges(modelId));
    }

    /** 节点 + 边的快照容器，用于版本快照导入。 */
    public record NodesAndEdges(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {}

    // 暴露给 Repository 共用的 Mapper（避免重复实例化 JsonCodec）
    public JsonCodec codec() { return codec; }
}

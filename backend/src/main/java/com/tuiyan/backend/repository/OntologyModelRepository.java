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
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本体模型聚合根仓储：负责 OntologyModel 与底层 4 张表（model + node + prop + edge）之间的读写编排。
 * <p>save 走"先删后插"的覆盖式策略，实现简单且与现有"全量保存"语义一致。
 * 业务方法签名与原 OntologyModelService 兼容，保持 controller 层无感知。
 */
@Repository
public class OntologyModelRepository {

    private final OntologyModelMapper modelMapper;
    private final OntologyNodeMapper nodeMapper;
    private final OntologyNodePropMapper propMapper;
    private final OntologyEdgeMapper edgeMapper;
    private final JsonCodec codec;

    public OntologyModelRepository(OntologyModelMapper modelMapper,
                                   OntologyNodeMapper nodeMapper,
                                   OntologyNodePropMapper propMapper,
                                   OntologyEdgeMapper edgeMapper,
                                   ObjectMapper objectMapper) {
        this.modelMapper = modelMapper;
        this.nodeMapper = nodeMapper;
        this.propMapper = propMapper;
        this.edgeMapper = edgeMapper;
        this.codec = new JsonCodec(objectMapper);
    }

    /** 计数；启动时种子判定用。 */
    public long count() {
        return modelMapper.selectCount(null);
    }

    /** 列表，按 updated_at 倒序。 */
    public List<OntologyModel> list() {
        List<OntologyModelPO> pos = modelMapper.selectList(
                new LambdaQueryWrapper<OntologyModelPO>().orderByDesc(OntologyModelPO::getUpdatedAt));
        List<OntologyModel> out = new ArrayList<>(pos.size());
        for (OntologyModelPO po : pos) {
            out.add(loadModel(po));
        }
        return out;
    }

    /** 按 id 加载完整模型（含节点、props、边）；不存在返回 null。 */
    public OntologyModel get(String id) {
        OntologyModelPO po = modelMapper.selectById(id);
        if (po == null) return null;
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
        if (modelMapper.selectById(m.getId()) == null) {
            modelMapper.insert(po);
        } else {
            modelMapper.updateById(po);
        }

        // 2. 删除旧的节点 / props / 边（外键级联会清 props，但显式删除更直观）
        deleteAllChildren(m.getId());

        // 3. 插入新的节点
        if (m.getGraphData() != null) {
            List<Map<String, Object>> nodes = m.getGraphData().getNodes();
            if (nodes != null) {
                for (Map<String, Object> n : nodes) {
                    insertNode(m.getId(), n);
                }
            }
            List<Map<String, Object>> edges = m.getGraphData().getEdges();
            if (edges != null) {
                for (Map<String, Object> e : edges) {
                    insertEdge(m.getId(), e);
                }
            }
        }
    }

    /** 物理删除模型；外键级联自动清理子表。 */
    @Transactional
    public boolean delete(String id) {
        return modelMapper.deleteById(id) > 0;
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

    // ---------- 内部转换 ----------

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
        g.setNodes(loadNodes(po.getId()));
        g.setEdges(loadEdges(po.getId()));
        m.setGraphData(g);
        return m;
    }

    private List<Map<String, Object>> loadNodes(String modelId) {
        List<OntologyNodePO> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<OntologyNodePO>().eq(OntologyNodePO::getModelId, modelId));
        // 一次性把该模型的所有 props 加载，按 (nodeId) 分组，避免 N+1
        List<OntologyNodePropPO> allProps = propMapper.selectList(
                new LambdaQueryWrapper<OntologyNodePropPO>().eq(OntologyNodePropPO::getModelId, modelId)
                        .orderByAsc(OntologyNodePropPO::getSortNo));
        Map<String, List<OntologyNodePropPO>> propsByNode = new LinkedHashMap<>();
        for (OntologyNodePropPO p : allProps) {
            propsByNode.computeIfAbsent(p.getNodeId(), k -> new ArrayList<>()).add(p);
        }
        List<Map<String, Object>> out = new ArrayList<>(nodes.size());
        for (OntologyNodePO n : nodes) {
            out.add(nodeToMap(n, propsByNode.getOrDefault(n.getId(), List.of())));
        }
        return out;
    }

    private List<Map<String, Object>> loadEdges(String modelId) {
        List<OntologyEdgePO> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<OntologyEdgePO>().eq(OntologyEdgePO::getModelId, modelId));
        List<Map<String, Object>> out = new ArrayList<>(edges.size());
        for (OntologyEdgePO e : edges) {
            out.add(edgeToMap(e));
        }
        return out;
    }

    private Map<String, Object> nodeToMap(OntologyNodePO n, List<OntologyNodePropPO> props) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        if (n.getLabel() != null) m.put("label", n.getLabel());
        if (n.getType() != null) m.put("type", n.getType());
        if (n.getSource() != null) m.put("source", n.getSource());
        if (n.getX() != null) m.put("x", n.getX());
        if (n.getY() != null) m.put("y", n.getY());
        if (n.getPredictedStep() != null) m.put("predictedStep", n.getPredictedStep());
        if (n.getPredictedIntent() != null) m.put("predictedIntent", n.getPredictedIntent());
        if (n.getConfidence() != null) m.put("confidence", n.getConfidence());
        if (n.getEffectiveProbability() != null) m.put("effectiveProbability", n.getEffectiveProbability());
        if (n.getExplanation() != null) m.put("explanation", n.getExplanation());
        if (!props.isEmpty()) {
            // 还原成原始 props 数组形式：[{key, value, source}, ...]
            List<Map<String, Object>> arr = new ArrayList<>(props.size());
            for (OntologyNodePropPO p : props) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("key", p.getPropKey());
                one.put("value", codec.decode(p.getPropValue(), p.getValueType()));
                if (p.getSource() != null) one.put("source", p.getSource());
                arr.add(one);
            }
            m.put("props", arr);
        }
        return m;
    }

    private Map<String, Object> edgeToMap(OntologyEdgePO e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("from", e.getFromNodeId());
        m.put("to", e.getToNodeId());
        if (e.getLabel() != null) m.put("label", e.getLabel());
        if (e.getSource() != null) m.put("source", e.getSource());
        if (Boolean.TRUE.equals(e.getRuleDriven())) m.put("rule_driven", true);
        if (e.getRuleId() != null) m.put("ruleId", e.getRuleId());
        return m;
    }

    private void insertNode(String modelId, Map<String, Object> n) {
        OntologyNodePO po = new OntologyNodePO();
        po.setId(asString(n.get("id")));
        po.setModelId(modelId);
        po.setLabel(asString(n.get("label")));
        po.setType(asString(n.get("type")));
        po.setSource(asString(n.get("source")));
        po.setX(asDouble(n.get("x")));
        po.setY(asDouble(n.get("y")));
        po.setPredictedStep(asInt(n.get("predictedStep")));
        po.setPredictedIntent(asString(n.get("predictedIntent")));
        po.setConfidence(asDouble(n.get("confidence")));
        po.setEffectiveProbability(asDouble(n.get("effectiveProbability")));
        po.setExplanation(asString(n.get("explanation")));
        nodeMapper.insert(po);

        // 节点的 props 数组拆为多行写入子表；保留 sortNo 用于回读时还原顺序
        Object propsObj = n.get("props");
        if (propsObj instanceof List<?> list) {
            int sortNo = 0;
            for (Object item : list) {
                if (!(item instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) item;
                OntologyNodePropPO ppo = new OntologyNodePropPO();
                ppo.setModelId(modelId);
                ppo.setNodeId(po.getId());
                ppo.setPropKey(asString(p.get("key")));
                JsonCodec.ValueAndType vt = codec.encode(p.get("value"));
                ppo.setPropValue(vt.value());
                ppo.setValueType(vt.valueType());
                ppo.setSource(asString(p.get("source")));
                ppo.setSortNo(sortNo++);
                propMapper.insert(ppo);
            }
        }
    }

    private void insertEdge(String modelId, Map<String, Object> e) {
        OntologyEdgePO po = new OntologyEdgePO();
        po.setId(asString(e.get("id")));
        po.setModelId(modelId);
        po.setFromNodeId(asString(e.get("from")));
        po.setToNodeId(asString(e.get("to")));
        po.setLabel(asString(e.get("label")));
        po.setSource(asString(e.get("source")));
        Object rd = e.get("rule_driven");
        po.setRuleDriven(rd instanceof Boolean ? (Boolean) rd : Boolean.FALSE);
        po.setRuleId(asString(e.get("ruleId")));
        edgeMapper.insert(po);
    }

    private static String asString(Object v) { return v == null ? null : String.valueOf(v); }

    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception ex) { return null; }
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception ex) { return null; }
    }

    /** 工具：取 model 子节点的工具方法供 OntologyVersionRepository 共用（按 modelId 拉节点 + props + 边）。 */
    public NodesAndEdges loadGraphForVersion(String modelId) {
        return new NodesAndEdges(loadNodes(modelId), loadEdges(modelId));
    }

    /** 节点 + 边的快照容器，用于版本快照导入。 */
    public record NodesAndEdges(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {}

    // 暴露给 Repository 共用的 Mapper（避免重复实例化 JsonCodec）
    public JsonCodec codec() { return codec; }
}

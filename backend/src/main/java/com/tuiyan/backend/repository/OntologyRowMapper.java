package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.OntologyEdgePO;
import com.tuiyan.backend.entity.OntologyNodePO;
import com.tuiyan.backend.entity.OntologyNodePropPO;
import com.tuiyan.backend.mapper.OntologyEdgeMapper;
import com.tuiyan.backend.mapper.OntologyNodeMapper;
import com.tuiyan.backend.mapper.OntologyNodePropMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tuiyan.backend.repository.RepoValueUtils.asDouble;
import static com.tuiyan.backend.repository.RepoValueUtils.asInt;
import static com.tuiyan.backend.repository.RepoValueUtils.asString;

/**
 * 本体模型的 node/edge/props 行映射协作类：承载 PO↔{@code Map} 双向行映射、props 拆行写入、
 * 以及一次性加载 props 规避 N+1 的读取逻辑。
 * <p>本类只做行映射，不持有事务边界，也不做"覆盖式删除"——这些由 {@link OntologyModelRepository}
 * 在其 {@code @Transactional} 方法内编排。props 的 sortNo 顺序契约、N+1 规避策略均在此保持不变。
 */
@Component
public class OntologyRowMapper {

    private final OntologyNodeMapper nodeMapper;
    private final OntologyNodePropMapper propMapper;
    private final OntologyEdgeMapper edgeMapper;
    private final JsonCodec codec;

    public OntologyRowMapper(OntologyNodeMapper nodeMapper,
                             OntologyNodePropMapper propMapper,
                             OntologyEdgeMapper edgeMapper,
                             ObjectMapper objectMapper) {
        this.nodeMapper = nodeMapper;
        this.propMapper = propMapper;
        this.edgeMapper = edgeMapper;
        this.codec = new JsonCodec(objectMapper);
    }

    // ---------- load（行映射 + N+1 规避） ----------

    public List<Map<String, Object>> loadNodes(String modelId) {
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

    public List<Map<String, Object>> loadEdges(String modelId) {
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
        if (n.getDerivedTablesJson() != null && !n.getDerivedTablesJson().isBlank()) {
            m.put("derived_tables", codec.readStringList(n.getDerivedTablesJson()));
        }
        if (n.getDerivedSource() != null) m.put("derived_source", n.getDerivedSource());
        if (n.getDerivedDatabase() != null) m.put("derived_database", n.getDerivedDatabase());
        if (n.getDerivedSourcesJson() != null && !n.getDerivedSourcesJson().isBlank()) {
            m.put("derived_sources", codec.readMapList(n.getDerivedSourcesJson()));
        }
        if (n.getAttributesJson() != null && !n.getAttributesJson().isBlank()) {
            m.put("attributes", codec.readMapList(n.getAttributesJson()));
        }
        if (n.getConstraintsJson() != null && !n.getConstraintsJson().isBlank()) {
            m.put("constraints", codec.readMapList(n.getConstraintsJson()));
        }
        if (n.getX() != null) m.put("x", n.getX());
        if (n.getY() != null) m.put("y", n.getY());
        if (n.getConfidence() != null) m.put("confidence", n.getConfidence());
        if (n.getEvidence() != null) m.put("evidence", n.getEvidence());
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
        if (e.getDerivedTablesJson() != null && !e.getDerivedTablesJson().isBlank()) {
            m.put("derived_tables", codec.readStringList(e.getDerivedTablesJson()));
        }
        if (e.getDerivedSource() != null) m.put("derived_source", e.getDerivedSource());
        if (e.getDerivedDatabase() != null) m.put("derived_database", e.getDerivedDatabase());
        if (e.getDerivedSourcesJson() != null && !e.getDerivedSourcesJson().isBlank()) {
            m.put("derived_sources", codec.readMapList(e.getDerivedSourcesJson()));
        }
        if (e.getConstraintsJson() != null && !e.getConstraintsJson().isBlank()) {
            m.put("constraints", codec.readMapList(e.getConstraintsJson()));
        }
        if (Boolean.TRUE.equals(e.getRuleDriven())) m.put("rule_driven", true);
        if (e.getRuleId() != null) m.put("ruleId", e.getRuleId());
        if (e.getRelType() != null) m.put("rel_type", e.getRelType());
        if (e.getEvidence() != null) m.put("evidence", e.getEvidence());
        if (e.getConfidence() != null) m.put("confidence", e.getConfidence());
        return m;
    }

    // ---------- insert（行映射 + props 拆行） ----------

    public void insertNode(String modelId, Map<String, Object> n) {
        OntologyNodePO po = new OntologyNodePO();
        po.setId(asString(n.get("id")));
        po.setModelId(modelId);
        po.setLabel(asString(n.get("label")));
        po.setType(asString(n.get("type")));
        po.setSource(asString(n.get("source")));
        po.setDerivedTablesJson(codec.toJson(n.get("derived_tables")));
        po.setDerivedSource(asString(n.get("derived_source")));
        po.setDerivedDatabase(asString(n.get("derived_database")));
        po.setDerivedSourcesJson(codec.toJson(n.get("derived_sources")));
        po.setAttributesJson(codec.toJson(n.get("attributes")));
        po.setConstraintsJson(codec.toJson(n.get("constraints")));
        po.setX(asDouble(n.get("x")));
        po.setY(asDouble(n.get("y")));
        po.setConfidence(asDouble(n.get("confidence")));
        po.setEvidence(asString(n.get("evidence")));
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

    public void insertEdge(String modelId, Map<String, Object> e) {
        OntologyEdgePO po = new OntologyEdgePO();
        po.setId(asString(e.get("id")));
        po.setModelId(modelId);
        po.setFromNodeId(asString(e.get("from")));
        po.setToNodeId(asString(e.get("to")));
        po.setLabel(asString(e.get("label")));
        po.setSource(asString(e.get("source")));
        po.setDerivedTablesJson(codec.toJson(e.get("derived_tables")));
        po.setDerivedSource(asString(e.get("derived_source")));
        po.setDerivedDatabase(asString(e.get("derived_database")));
        po.setDerivedSourcesJson(codec.toJson(e.get("derived_sources")));
        po.setConstraintsJson(codec.toJson(e.get("constraints")));
        Object rd = e.get("rule_driven");
        po.setRuleDriven(rd instanceof Boolean ? (Boolean) rd : Boolean.FALSE);
        po.setRuleId(asString(e.get("ruleId")));
        po.setRelType(asString(e.get("rel_type")));
        po.setEvidence(asString(e.get("evidence")));
        po.setConfidence(asDouble(e.get("confidence")));
        edgeMapper.insert(po);
    }
}

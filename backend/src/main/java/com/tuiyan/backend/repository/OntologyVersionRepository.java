package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.OntologyModelVersionPO;
import com.tuiyan.backend.entity.OntologyVersionEdgePO;
import com.tuiyan.backend.entity.OntologyVersionNodePO;
import com.tuiyan.backend.entity.OntologyVersionNodePropPO;
import com.tuiyan.backend.mapper.OntologyModelVersionMapper;
import com.tuiyan.backend.mapper.OntologyVersionEdgeMapper;
import com.tuiyan.backend.mapper.OntologyVersionNodeMapper;
import com.tuiyan.backend.mapper.OntologyVersionNodePropMapper;
import com.tuiyan.backend.model.OntologyModel;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型版本快照仓储：负责保存模型快照、列出版本、按时间戳恢复。
 * <p>每模型最多保留 100 个快照，超出按 snapshot_at 升序删除最早。
 */
@Repository
public class OntologyVersionRepository {

    private static final int MAX_VERSIONS = 100;

    private final OntologyModelVersionMapper versionMapper;
    private final OntologyVersionNodeMapper nodeMapper;
    private final OntologyVersionNodePropMapper propMapper;
    private final OntologyVersionEdgeMapper edgeMapper;
    private final JsonCodec codec;

    public OntologyVersionRepository(OntologyModelVersionMapper versionMapper,
                                     OntologyVersionNodeMapper nodeMapper,
                                     OntologyVersionNodePropMapper propMapper,
                                     OntologyVersionEdgeMapper edgeMapper,
                                     ObjectMapper objectMapper) {
        this.versionMapper = versionMapper;
        this.nodeMapper = nodeMapper;
        this.propMapper = propMapper;
        this.edgeMapper = edgeMapper;
        this.codec = new JsonCodec(objectMapper);
    }

    /**
     * 把当前模型快照写入版本表，并淘汰超过 MAX_VERSIONS 的最旧版本。
     */
    @Transactional
    public void snapshot(OntologyModel current) {
        OntologyModelVersionPO version = new OntologyModelVersionPO();
        version.setModelId(current.getId());
        version.setSnapshotAt(System.currentTimeMillis());
        version.setTitle(current.getTitle());
        version.setDescription(current.getDesc());
        versionMapper.insert(version);
        Long versionId = version.getId();

        if (current.getGraphData() != null) {
            if (current.getGraphData().getNodes() != null) {
                for (Map<String, Object> n : current.getGraphData().getNodes()) {
                    insertVersionNode(versionId, n);
                }
            }
            if (current.getGraphData().getEdges() != null) {
                for (Map<String, Object> e : current.getGraphData().getEdges()) {
                    insertVersionEdge(versionId, e);
                }
            }
        }

        evictOld(current.getId());
    }

    /** 列出模型的所有版本元信息 + 节点/边数量摘要，按 snapshot_at 倒序。 */
    public List<Map<String, Object>> listVersions(String modelId) {
        List<OntologyModelVersionPO> versions = versionMapper.selectList(
                new LambdaQueryWrapper<OntologyModelVersionPO>()
                        .eq(OntologyModelVersionPO::getModelId, modelId)
                        .orderByDesc(OntologyModelVersionPO::getSnapshotAt));
        List<Map<String, Object>> out = new ArrayList<>(versions.size());
        for (OntologyModelVersionPO v : versions) {
            long nodeCount = nodeMapper.selectCount(
                    new LambdaQueryWrapper<OntologyVersionNodePO>().eq(OntologyVersionNodePO::getVersionId, v.getId()));
            long edgeCount = edgeMapper.selectCount(
                    new LambdaQueryWrapper<OntologyVersionEdgePO>().eq(OntologyVersionEdgePO::getVersionId, v.getId()));
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("timestamp", v.getSnapshotAt());
            info.put("nodeCount", (int) nodeCount);
            info.put("edgeCount", (int) edgeCount);
            // fileSize 不再适用，但保留字段以兼容前端结构
            info.put("fileSize", 0);
            out.add(info);
        }
        return out;
    }

    /** 按时间戳查找版本快照，转换回 OntologyModel；找不到返回 null。 */
    public OntologyModel loadByTimestamp(String modelId, long timestamp) {
        OntologyModelVersionPO version = versionMapper.selectOne(
                new LambdaQueryWrapper<OntologyModelVersionPO>()
                        .eq(OntologyModelVersionPO::getModelId, modelId)
                        .eq(OntologyModelVersionPO::getSnapshotAt, timestamp)
                        .last("LIMIT 1"));
        if (version == null) return null;

        OntologyModel m = new OntologyModel();
        m.setId(modelId);
        m.setTitle(version.getTitle());
        m.setDesc(version.getDescription());

        OntologyModel.GraphData g = new OntologyModel.GraphData();
        g.setNodes(loadVersionNodes(version.getId()));
        g.setEdges(loadVersionEdges(version.getId()));
        m.setGraphData(g);
        return m;
    }

    // ---------- 内部 ----------

    private void evictOld(String modelId) {
        List<OntologyModelVersionPO> all = versionMapper.selectList(
                new LambdaQueryWrapper<OntologyModelVersionPO>()
                        .eq(OntologyModelVersionPO::getModelId, modelId)
                        .orderByAsc(OntologyModelVersionPO::getSnapshotAt));
        int over = all.size() - MAX_VERSIONS;
        for (int i = 0; i < over; i++) {
            versionMapper.deleteById(all.get(i).getId());
        }
    }

    private void insertVersionNode(Long versionId, Map<String, Object> n) {
        OntologyVersionNodePO po = new OntologyVersionNodePO();
        po.setVersionId(versionId);
        po.setNodeId(asString(n.get("id")));
        po.setLabel(asString(n.get("label")));
        po.setType(asString(n.get("type")));
        po.setSource(asString(n.get("source")));
        po.setDerivedTablesJson(codec.toJson(n.get("derived_tables")));
        po.setDerivedSource(asString(n.get("derived_source")));
        po.setDerivedDatabase(asString(n.get("derived_database")));
        po.setAttributesJson(codec.toJson(n.get("attributes")));
        po.setConstraintsJson(codec.toJson(n.get("constraints")));
        po.setX(asDouble(n.get("x")));
        po.setY(asDouble(n.get("y")));
        po.setConfidence(asDouble(n.get("confidence")));
        po.setEvidence(asString(n.get("evidence")));
        nodeMapper.insert(po);

        Object propsObj = n.get("props");
        if (propsObj instanceof List<?> list) {
            int sortNo = 0;
            for (Object item : list) {
                if (!(item instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) item;
                OntologyVersionNodePropPO ppo = new OntologyVersionNodePropPO();
                ppo.setVersionId(versionId);
                ppo.setNodeId(po.getNodeId());
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

    private void insertVersionEdge(Long versionId, Map<String, Object> e) {
        OntologyVersionEdgePO po = new OntologyVersionEdgePO();
        po.setVersionId(versionId);
        po.setEdgeId(asString(e.get("id")));
        po.setFromNodeId(asString(e.get("from")));
        po.setToNodeId(asString(e.get("to")));
        po.setLabel(asString(e.get("label")));
        po.setSource(asString(e.get("source")));
        po.setDerivedTablesJson(codec.toJson(e.get("derived_tables")));
        po.setDerivedSource(asString(e.get("derived_source")));
        po.setDerivedDatabase(asString(e.get("derived_database")));
        po.setConstraintsJson(codec.toJson(e.get("constraints")));
        Object rd = e.get("rule_driven");
        po.setRuleDriven(rd instanceof Boolean ? (Boolean) rd : Boolean.FALSE);
        po.setRuleId(asString(e.get("ruleId")));
        po.setRelType(asString(e.get("rel_type")));
        po.setEvidence(asString(e.get("evidence")));
        po.setConfidence(asDouble(e.get("confidence")));
        edgeMapper.insert(po);
    }

    private List<Map<String, Object>> loadVersionNodes(Long versionId) {
        List<OntologyVersionNodePO> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<OntologyVersionNodePO>().eq(OntologyVersionNodePO::getVersionId, versionId));
        List<OntologyVersionNodePropPO> allProps = propMapper.selectList(
                new LambdaQueryWrapper<OntologyVersionNodePropPO>().eq(OntologyVersionNodePropPO::getVersionId, versionId)
                        .orderByAsc(OntologyVersionNodePropPO::getSortNo));
        Map<String, List<OntologyVersionNodePropPO>> propsByNode = new LinkedHashMap<>();
        for (OntologyVersionNodePropPO p : allProps) {
            propsByNode.computeIfAbsent(p.getNodeId(), k -> new ArrayList<>()).add(p);
        }

        List<Map<String, Object>> out = new ArrayList<>(nodes.size());
        for (OntologyVersionNodePO n : nodes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getNodeId());
            if (n.getLabel() != null) m.put("label", n.getLabel());
            if (n.getType() != null) m.put("type", n.getType());
            if (n.getSource() != null) m.put("source", n.getSource());
            if (n.getDerivedTablesJson() != null && !n.getDerivedTablesJson().isBlank()) {
                m.put("derived_tables", codec.readStringList(n.getDerivedTablesJson()));
            }
            if (n.getDerivedSource() != null) m.put("derived_source", n.getDerivedSource());
            if (n.getDerivedDatabase() != null) m.put("derived_database", n.getDerivedDatabase());
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
            List<OntologyVersionNodePropPO> props = propsByNode.getOrDefault(n.getNodeId(), List.of());
            if (!props.isEmpty()) {
                List<Map<String, Object>> arr = new ArrayList<>(props.size());
                for (OntologyVersionNodePropPO p : props) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("key", p.getPropKey());
                    one.put("value", codec.decode(p.getPropValue(), p.getValueType()));
                    if (p.getSource() != null) one.put("source", p.getSource());
                    arr.add(one);
                }
                m.put("props", arr);
            }
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> loadVersionEdges(Long versionId) {
        List<OntologyVersionEdgePO> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<OntologyVersionEdgePO>().eq(OntologyVersionEdgePO::getVersionId, versionId));
        List<Map<String, Object>> out = new ArrayList<>(edges.size());
        for (OntologyVersionEdgePO e : edges) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getEdgeId());
            m.put("from", e.getFromNodeId());
            m.put("to", e.getToNodeId());
            if (e.getLabel() != null) m.put("label", e.getLabel());
            if (e.getSource() != null) m.put("source", e.getSource());
            if (e.getDerivedTablesJson() != null && !e.getDerivedTablesJson().isBlank()) {
                m.put("derived_tables", codec.readStringList(e.getDerivedTablesJson()));
            }
            if (e.getDerivedSource() != null) m.put("derived_source", e.getDerivedSource());
            if (e.getDerivedDatabase() != null) m.put("derived_database", e.getDerivedDatabase());
            if (e.getConstraintsJson() != null && !e.getConstraintsJson().isBlank()) {
                m.put("constraints", codec.readMapList(e.getConstraintsJson()));
            }
            if (Boolean.TRUE.equals(e.getRuleDriven())) m.put("rule_driven", true);
            if (e.getRuleId() != null) m.put("ruleId", e.getRuleId());
            if (e.getRelType() != null) m.put("rel_type", e.getRelType());
            if (e.getEvidence() != null) m.put("evidence", e.getEvidence());
            if (e.getConfidence() != null) m.put("confidence", e.getConfidence());
            out.add(m);
        }
        return out;
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
}

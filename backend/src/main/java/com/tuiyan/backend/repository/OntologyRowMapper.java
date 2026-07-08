package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.OntologyEdgePO;
import com.tuiyan.backend.entity.OntologyNodePO;
import com.tuiyan.backend.entity.OntologyNodePropPO;
import com.tuiyan.backend.mapper.OntologyEdgeMapper;
import com.tuiyan.backend.mapper.OntologyNodeMapper;
import com.tuiyan.backend.mapper.OntologyNodePropMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tuiyan.backend.repository.RepoValueUtils.asDouble;
import static com.tuiyan.backend.repository.RepoValueUtils.asInt;
import static com.tuiyan.backend.repository.RepoValueUtils.asLong;
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
    private final JdbcTemplate jdbc;
    private final JsonCodec codec;

    public OntologyRowMapper(OntologyNodeMapper nodeMapper,
                             OntologyNodePropMapper propMapper,
                             OntologyEdgeMapper edgeMapper,
                             JdbcTemplate jdbc,
                             ObjectMapper objectMapper) {
        this.nodeMapper = nodeMapper;
        this.propMapper = propMapper;
        this.edgeMapper = edgeMapper;
        this.jdbc = jdbc;
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
        if (n.getDomain() != null) m.put("domain", n.getDomain());
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
        if (e.getDomain() != null) m.put("domain", e.getDomain());
        if (e.getConstraintsJson() != null && !e.getConstraintsJson().isBlank()) {
            m.put("constraints", codec.readMapList(e.getConstraintsJson()));
        }
        if (Boolean.TRUE.equals(e.getRuleDriven())) m.put("rule_driven", true);
        if (e.getRuleId() != null) m.put("ruleId", e.getRuleId());
        if (e.getRelType() != null) m.put("rel_type", e.getRelType());
        if (e.getEvidence() != null) m.put("evidence", e.getEvidence());
        if (e.getConfidence() != null) m.put("confidence", e.getConfidence());
        if (e.getEvidencesJson() != null && !e.getEvidencesJson().isBlank()) {
            m.put("evidences", codec.readStringList(e.getEvidencesJson()));
        }
        if (e.getReviewStatus() != null && !e.getReviewStatus().isBlank()) m.put("review_status", e.getReviewStatus());
        if (e.getReviewNote() != null && !e.getReviewNote().isBlank()) m.put("review_note", e.getReviewNote());
        if (e.getReviewedAt() != null) m.put("reviewed_at", e.getReviewedAt());
        return m;
    }

    // ---------- insert（行映射 + props 拆行） ----------

    public void insertNode(String modelId, Map<String, Object> n) {
        OntologyNodePO po = buildNodePO(modelId, n);
        nodeMapper.insert(po);
        for (OntologyNodePropPO ppo : buildPropPOs(modelId, po.getId(), n)) {
            propMapper.insert(ppo);
        }
    }

    public void insertEdge(String modelId, Map<String, Object> e) {
        edgeMapper.insert(buildEdgePO(modelId, e));
    }

    // ---------- 批量写入（覆盖式全量保存：万节点逐行 insert 往返开销极大，改 JdbcTemplate.batchUpdate；
    //            在调用方 @Transactional 内、走事务绑定连接，与其它写入同一事务，原子性不变） ----------

    private static final String NODE_INSERT_SQL =
            "INSERT INTO ontology_node (id, model_id, label, type, source, derived_tables_json, derived_source, " +
            "derived_database, derived_sources_json, domain, attributes_json, constraints_json, x, y, confidence, evidence) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String PROP_INSERT_SQL =
            "INSERT INTO ontology_node_prop (model_id, node_id, prop_key, prop_value, value_type, source, sort_no) " +
            "VALUES (?,?,?,?,?,?,?)";
    private static final String EDGE_INSERT_SQL =
            "INSERT INTO ontology_edge (id, model_id, from_node_id, to_node_id, label, source, derived_tables_json, " +
            "derived_source, derived_database, derived_sources_json, domain, constraints_json, rule_driven, rule_id, " +
            "rel_type, evidence, confidence, evidences_json, review_status, review_note, reviewed_at) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    /** 单批行数上限：控制单条 batch 的报文体积/内存，超大图分批 flush。 */
    private static final int BATCH_CHUNK = 1000;

    /** 批量插入节点 + 其 props（列/编码与 {@link #insertNode} 完全一致，仅换成一次批量往返）。 */
    public void insertNodesBatch(String modelId, List<Map<String, Object>> nodes) {
        if (nodes == null || nodes.isEmpty()) return;
        List<Object[]> nodeArgs = new ArrayList<>(nodes.size());
        List<Object[]> propArgs = new ArrayList<>();
        for (Map<String, Object> n : nodes) {
            OntologyNodePO po = buildNodePO(modelId, n);
            nodeArgs.add(toNodeArgs(po));
            for (OntologyNodePropPO pp : buildPropPOs(modelId, po.getId(), n)) {
                propArgs.add(toPropArgs(pp));
            }
        }
        batchInChunks(NODE_INSERT_SQL, nodeArgs);
        batchInChunks(PROP_INSERT_SQL, propArgs);
    }

    /** 批量插入边（列/编码与 {@link #insertEdge} 完全一致）。 */
    public void insertEdgesBatch(String modelId, List<Map<String, Object>> edges) {
        if (edges == null || edges.isEmpty()) return;
        List<Object[]> args = new ArrayList<>(edges.size());
        for (Map<String, Object> e : edges) {
            args.add(toEdgeArgs(buildEdgePO(modelId, e)));
        }
        batchInChunks(EDGE_INSERT_SQL, args);
    }

    private void batchInChunks(String sql, List<Object[]> args) {
        for (int i = 0; i < args.size(); i += BATCH_CHUNK) {
            jdbc.batchUpdate(sql, args.subList(i, Math.min(i + BATCH_CHUNK, args.size())));
        }
    }

    // ---------- PO 构建（单插/批插共用同一份映射，杜绝编码分叉） ----------

    private OntologyNodePO buildNodePO(String modelId, Map<String, Object> n) {
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
        po.setDomain(asString(n.get("domain")));
        po.setAttributesJson(codec.toJson(n.get("attributes")));
        po.setConstraintsJson(codec.toJson(n.get("constraints")));
        po.setX(asDouble(n.get("x")));
        po.setY(asDouble(n.get("y")));
        po.setConfidence(asDouble(n.get("confidence")));
        po.setEvidence(asString(n.get("evidence")));
        return po;
    }

    /** 节点的 props 数组拆为多行；保留 sortNo 用于回读时还原顺序。 */
    private List<OntologyNodePropPO> buildPropPOs(String modelId, String nodeId, Map<String, Object> n) {
        Object propsObj = n.get("props");
        if (!(propsObj instanceof List<?> list) || list.isEmpty()) return List.of();
        List<OntologyNodePropPO> out = new ArrayList<>(list.size());
        int sortNo = 0;
        for (Object item : list) {
            if (!(item instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) item;
            OntologyNodePropPO ppo = new OntologyNodePropPO();
            ppo.setModelId(modelId);
            ppo.setNodeId(nodeId);
            ppo.setPropKey(asString(p.get("key")));
            JsonCodec.ValueAndType vt = codec.encode(p.get("value"));
            ppo.setPropValue(vt.value());
            ppo.setValueType(vt.valueType());
            ppo.setSource(asString(p.get("source")));
            ppo.setSortNo(sortNo++);
            out.add(ppo);
        }
        return out;
    }

    private OntologyEdgePO buildEdgePO(String modelId, Map<String, Object> e) {
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
        po.setDomain(asString(e.get("domain")));
        po.setConstraintsJson(codec.toJson(e.get("constraints")));
        Object rd = e.get("rule_driven");
        po.setRuleDriven(rd instanceof Boolean ? (Boolean) rd : Boolean.FALSE);
        po.setRuleId(asString(e.get("ruleId")));
        po.setRelType(asString(e.get("rel_type")));
        po.setEvidence(asString(e.get("evidence")));
        po.setConfidence(asDouble(e.get("confidence")));
        po.setEvidencesJson(codec.toJson(e.get("evidences")));
        po.setReviewStatus(asString(e.get("review_status")));
        po.setReviewNote(asString(e.get("review_note")));
        po.setReviewedAt(asLong(e.get("reviewed_at")));
        return po;
    }

    // 列顺序与上面的 *_INSERT_SQL 一一对应，改一处必须同步改另一处。
    private Object[] toNodeArgs(OntologyNodePO p) {
        return new Object[]{ p.getId(), p.getModelId(), p.getLabel(), p.getType(), p.getSource(),
                p.getDerivedTablesJson(), p.getDerivedSource(), p.getDerivedDatabase(), p.getDerivedSourcesJson(),
                p.getDomain(), p.getAttributesJson(), p.getConstraintsJson(), p.getX(), p.getY(),
                p.getConfidence(), p.getEvidence() };
    }

    private Object[] toPropArgs(OntologyNodePropPO p) {
        return new Object[]{ p.getModelId(), p.getNodeId(), p.getPropKey(), p.getPropValue(),
                p.getValueType(), p.getSource(), p.getSortNo() };
    }

    private Object[] toEdgeArgs(OntologyEdgePO p) {
        return new Object[]{ p.getId(), p.getModelId(), p.getFromNodeId(), p.getToNodeId(), p.getLabel(),
                p.getSource(), p.getDerivedTablesJson(), p.getDerivedSource(), p.getDerivedDatabase(),
                p.getDerivedSourcesJson(), p.getDomain(), p.getConstraintsJson(), p.getRuleDriven(),
                p.getRuleId(), p.getRelType(), p.getEvidence(), p.getConfidence(),
                p.getEvidencesJson(), p.getReviewStatus(), p.getReviewNote(), p.getReviewedAt() };
    }
}

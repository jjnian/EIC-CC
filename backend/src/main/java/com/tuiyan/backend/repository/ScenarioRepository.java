package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.ScenarioChainStepPO;
import com.tuiyan.backend.entity.ScenarioConstraintPO;
import com.tuiyan.backend.entity.ScenarioEdgePO;
import com.tuiyan.backend.entity.ScenarioNodeExplanationPO;
import com.tuiyan.backend.entity.ScenarioNodePO;
import com.tuiyan.backend.entity.ScenarioPO;
import com.tuiyan.backend.entity.ScenarioSeedPO;
import com.tuiyan.backend.mapper.ScenarioChainStepMapper;
import com.tuiyan.backend.mapper.ScenarioConstraintMapper;
import com.tuiyan.backend.mapper.ScenarioEdgeMapper;
import com.tuiyan.backend.mapper.ScenarioMapper;
import com.tuiyan.backend.mapper.ScenarioNodeExplanationMapper;
import com.tuiyan.backend.mapper.ScenarioNodeMapper;
import com.tuiyan.backend.mapper.ScenarioSeedMapper;
import com.tuiyan.backend.model.Constraint;
import com.tuiyan.backend.model.NodeExplanation;
import com.tuiyan.backend.model.PredictionDag;
import com.tuiyan.backend.model.Scenario;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推演分支聚合根仓储：负责 Scenario 与底层 7 张表的读写编排。
 * <p>save 走覆盖式策略：删除该 scenario 下所有子表行后重新插入。
 */
@Repository
public class ScenarioRepository {

    private final ScenarioMapper scenarioMapper;
    private final ScenarioSeedMapper seedMapper;
    private final ScenarioNodeMapper nodeMapper;
    private final ScenarioEdgeMapper edgeMapper;
    private final ScenarioChainStepMapper chainStepMapper;
    private final ScenarioConstraintMapper constraintMapper;
    private final ScenarioNodeExplanationMapper explanationMapper;
    private final JsonCodec codec;

    public ScenarioRepository(ScenarioMapper scenarioMapper,
                              ScenarioSeedMapper seedMapper,
                              ScenarioNodeMapper nodeMapper,
                              ScenarioEdgeMapper edgeMapper,
                              ScenarioChainStepMapper chainStepMapper,
                              ScenarioConstraintMapper constraintMapper,
                              ScenarioNodeExplanationMapper explanationMapper,
                              ObjectMapper objectMapper) {
        this.scenarioMapper = scenarioMapper;
        this.seedMapper = seedMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.chainStepMapper = chainStepMapper;
        this.constraintMapper = constraintMapper;
        this.explanationMapper = explanationMapper;
        this.codec = new JsonCodec(objectMapper);
    }

    /** 列出所有分支；modelId 为 null 时返回全部。 */
    public List<Scenario> list(String modelId) {
        LambdaQueryWrapper<ScenarioPO> qw = new LambdaQueryWrapper<>();
        if (modelId != null && !modelId.isBlank()) {
            qw.eq(ScenarioPO::getModelId, modelId);
        }
        qw.orderByDesc(ScenarioPO::getCreatedAt);
        List<ScenarioPO> pos = scenarioMapper.selectList(qw);
        List<Scenario> out = new ArrayList<>(pos.size());
        for (ScenarioPO po : pos) {
            out.add(loadScenario(po));
        }
        return out;
    }

    /** 按 id 加载单个分支（含完整 dag）。 */
    public Scenario get(String id) {
        ScenarioPO po = scenarioMapper.selectById(id);
        if (po == null) return null;
        return loadScenario(po);
    }

    /**
     * 全量保存（覆盖式）：删旧子表行 → 插新行。
     */
    @Transactional
    public void save(Scenario s) {
        ScenarioPO po = toPO(s);
        if (scenarioMapper.selectById(s.getId()) == null) {
            scenarioMapper.insert(po);
        } else {
            scenarioMapper.updateById(po);
        }
        deleteAllChildren(s.getId());
        insertChildren(s);
    }

    /** 仅写入/更新单个节点的解释缓存（用于流式增量写）。 */
    @Transactional
    public void upsertExplanation(String scenarioId, String nodeId, NodeExplanation explanation) {
        explanationMapper.delete(new LambdaQueryWrapper<ScenarioNodeExplanationPO>()
                .eq(ScenarioNodeExplanationPO::getScenarioId, scenarioId)
                .eq(ScenarioNodeExplanationPO::getNodeId, nodeId));
        ScenarioNodeExplanationPO po = new ScenarioNodeExplanationPO();
        po.setScenarioId(scenarioId);
        po.setNodeId(nodeId);
        po.setEvidence(explanation.getEvidence());
        po.setAssumptions(explanation.getAssumptions());
        po.setCounterexamples(explanation.getCounterexamples());
        po.setGeneratedAt(explanation.getGeneratedAt());
        po.setModelName(explanation.getModelName());
        explanationMapper.insert(po);
    }

    /**
     * 物理删除分支 + 级联删除所有以该分支为祖先的子分支。
     * @return 实际删除的分支数量（含本身）
     */
    @Transactional
    public int delete(String id) {
        // 收集以 id 为祖先的所有分支
        List<ScenarioPO> all = scenarioMapper.selectList(null);
        Map<String, String> parentMap = new HashMap<>();
        for (ScenarioPO p : all) parentMap.put(p.getId(), p.getParentBranchId());

        List<String> toDelete = new ArrayList<>();
        toDelete.add(id);
        for (ScenarioPO s : all) {
            String cur = s.getParentBranchId();
            while (cur != null) {
                if (cur.equals(id)) {
                    toDelete.add(s.getId());
                    break;
                }
                cur = parentMap.get(cur);
            }
        }

        int total = 0;
        for (String d : toDelete) {
            // 外键 ON DELETE CASCADE 会清子表
            if (scenarioMapper.deleteById(d) > 0) total++;
        }
        return total;
    }

    // ---------- 内部 ----------

    private void deleteAllChildren(String scenarioId) {
        seedMapper.delete(new LambdaQueryWrapper<ScenarioSeedPO>().eq(ScenarioSeedPO::getScenarioId, scenarioId));
        nodeMapper.delete(new LambdaQueryWrapper<ScenarioNodePO>().eq(ScenarioNodePO::getScenarioId, scenarioId));
        edgeMapper.delete(new LambdaQueryWrapper<ScenarioEdgePO>().eq(ScenarioEdgePO::getScenarioId, scenarioId));
        chainStepMapper.delete(new LambdaQueryWrapper<ScenarioChainStepPO>().eq(ScenarioChainStepPO::getScenarioId, scenarioId));
        constraintMapper.delete(new LambdaQueryWrapper<ScenarioConstraintPO>().eq(ScenarioConstraintPO::getScenarioId, scenarioId));
        explanationMapper.delete(new LambdaQueryWrapper<ScenarioNodeExplanationPO>().eq(ScenarioNodeExplanationPO::getScenarioId, scenarioId));
    }

    private void insertChildren(Scenario s) {
        // seeds
        if (s.getSeeds() != null) {
            int sortNo = 0;
            for (String seed : s.getSeeds()) {
                ScenarioSeedPO po = new ScenarioSeedPO();
                po.setScenarioId(s.getId());
                po.setNodeId(seed);
                po.setSortNo(sortNo++);
                seedMapper.insert(po);
            }
        }
        // dag
        PredictionDag dag = s.getDag();
        if (dag != null) {
            if (dag.getNodes() != null) {
                for (Map<String, Object> n : dag.getNodes()) {
                    insertScenarioNode(s.getId(), n);
                }
            }
            if (dag.getEdges() != null) {
                for (Map<String, Object> e : dag.getEdges()) {
                    insertScenarioEdge(s.getId(), e);
                }
            }
            if (dag.getChain() != null) {
                for (Map<String, Object> step : dag.getChain()) {
                    insertChainStep(s.getId(), step);
                }
            }
            if (dag.getConstraints() != null) {
                for (Constraint c : dag.getConstraints()) {
                    insertConstraint(s.getId(), c);
                }
            }
            if (dag.getExplanations() != null) {
                for (Map.Entry<String, NodeExplanation> entry : dag.getExplanations().entrySet()) {
                    NodeExplanation ne = entry.getValue();
                    if (ne == null) continue;
                    ScenarioNodeExplanationPO po = new ScenarioNodeExplanationPO();
                    po.setScenarioId(s.getId());
                    po.setNodeId(entry.getKey());
                    po.setEvidence(ne.getEvidence());
                    po.setAssumptions(ne.getAssumptions());
                    po.setCounterexamples(ne.getCounterexamples());
                    po.setGeneratedAt(ne.getGeneratedAt());
                    po.setModelName(ne.getModelName());
                    explanationMapper.insert(po);
                }
            }
        }
    }

    private ScenarioPO toPO(Scenario s) {
        ScenarioPO po = new ScenarioPO();
        po.setId(s.getId());
        po.setModelId(s.getModelId());
        po.setParentBranchId(s.getParentBranchId());
        po.setName(s.getName());
        po.setIntent(s.getIntent());
        po.setSteps(s.getSteps());
        po.setPrompt(s.getPrompt());
        po.setRawPrompt(s.getRawPrompt());
        po.setCreatedAt(s.getCreatedAt());
        return po;
    }

    private Scenario loadScenario(ScenarioPO po) {
        Scenario s = new Scenario();
        s.setId(po.getId());
        s.setModelId(po.getModelId());
        s.setParentBranchId(po.getParentBranchId());
        s.setName(po.getName());
        s.setIntent(po.getIntent());
        s.setSteps(po.getSteps() == null ? 0 : po.getSteps());
        s.setPrompt(po.getPrompt());
        s.setRawPrompt(po.getRawPrompt());
        s.setCreatedAt(po.getCreatedAt() == null ? 0L : po.getCreatedAt());

        // seeds
        List<ScenarioSeedPO> seeds = seedMapper.selectList(
                new LambdaQueryWrapper<ScenarioSeedPO>()
                        .eq(ScenarioSeedPO::getScenarioId, po.getId())
                        .orderByAsc(ScenarioSeedPO::getSortNo));
        List<String> seedIds = new ArrayList<>(seeds.size());
        for (ScenarioSeedPO sp : seeds) seedIds.add(sp.getNodeId());
        s.setSeeds(seedIds);

        // dag
        PredictionDag dag = new PredictionDag();
        dag.setIntent(po.getIntent());
        dag.setNodes(loadScenarioNodes(po.getId()));
        dag.setEdges(loadScenarioEdges(po.getId()));
        dag.setChain(loadChainSteps(po.getId()));
        dag.setConstraints(loadConstraints(po.getId()));
        dag.setExplanations(loadExplanations(po.getId()));
        s.setDag(dag);
        return s;
    }

    private List<Map<String, Object>> loadScenarioNodes(String scenarioId) {
        List<ScenarioNodePO> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<ScenarioNodePO>().eq(ScenarioNodePO::getScenarioId, scenarioId));
        List<Map<String, Object>> out = new ArrayList<>(nodes.size());
        for (ScenarioNodePO n : nodes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getNodeId());
            if (n.getLabel() != null) m.put("label", n.getLabel());
            if (n.getType() != null) m.put("type", n.getType());
            if (n.getSource() != null) m.put("source", n.getSource());
            if (n.getX() != null) m.put("x", n.getX());
            if (n.getY() != null) m.put("y", n.getY());
            if (n.getPredictedStep() != null) m.put("predictedStep", n.getPredictedStep());
            if (n.getConfidence() != null) m.put("confidence", n.getConfidence());
            if (n.getEffectiveProbability() != null) m.put("effectiveProbability", n.getEffectiveProbability());
            if (n.getExplanation() != null) m.put("explanation", n.getExplanation());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> loadScenarioEdges(String scenarioId) {
        List<ScenarioEdgePO> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<ScenarioEdgePO>().eq(ScenarioEdgePO::getScenarioId, scenarioId));
        List<Map<String, Object>> out = new ArrayList<>(edges.size());
        for (ScenarioEdgePO e : edges) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getEdgeId());
            m.put("from", e.getFromNodeId());
            m.put("to", e.getToNodeId());
            if (e.getLabel() != null) m.put("label", e.getLabel());
            if (e.getSource() != null) m.put("source", e.getSource());
            if (Boolean.TRUE.equals(e.getRuleDriven())) m.put("rule_driven", true);
            if (e.getRuleId() != null) m.put("ruleId", e.getRuleId());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> loadChainSteps(String scenarioId) {
        List<ScenarioChainStepPO> steps = chainStepMapper.selectList(
                new LambdaQueryWrapper<ScenarioChainStepPO>()
                        .eq(ScenarioChainStepPO::getScenarioId, scenarioId)
                        .orderByAsc(ScenarioChainStepPO::getStepNo));
        List<Map<String, Object>> out = new ArrayList<>(steps.size());
        for (ScenarioChainStepPO s : steps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", s.getStepNo());
            m.put("nodeId", s.getNodeId());
            // triggered_by_json 还原为字符串列表
            if (s.getTriggeredByJson() != null) {
                m.put("triggeredBy", codec.readStringList(s.getTriggeredByJson()));
            }
            if (s.getRuleId() != null) m.put("ruleId", s.getRuleId());
            if (s.getExplanation() != null) m.put("explanation", s.getExplanation());
            if (s.getConfidence() != null) m.put("confidence", s.getConfidence());
            if (s.getEffectiveProbability() != null) m.put("effectiveProbability", s.getEffectiveProbability());
            if (s.getCumulativeCredibility() != null) m.put("cumulativeCredibility", s.getCumulativeCredibility());
            out.add(m);
        }
        return out;
    }

    private List<Constraint> loadConstraints(String scenarioId) {
        List<ScenarioConstraintPO> rows = constraintMapper.selectList(
                new LambdaQueryWrapper<ScenarioConstraintPO>().eq(ScenarioConstraintPO::getScenarioId, scenarioId));
        if (rows.isEmpty()) return null;
        List<Constraint> out = new ArrayList<>(rows.size());
        for (ScenarioConstraintPO p : rows) {
            Constraint c = new Constraint();
            c.setNodeId(p.getNodeId());
            c.setMode(p.getMode());
            c.setNote(p.getNote());
            c.setProbability(p.getProbability());
            out.add(c);
        }
        return out;
    }

    private Map<String, NodeExplanation> loadExplanations(String scenarioId) {
        List<ScenarioNodeExplanationPO> rows = explanationMapper.selectList(
                new LambdaQueryWrapper<ScenarioNodeExplanationPO>().eq(ScenarioNodeExplanationPO::getScenarioId, scenarioId));
        if (rows.isEmpty()) return null;
        Map<String, NodeExplanation> out = new HashMap<>();
        for (ScenarioNodeExplanationPO p : rows) {
            NodeExplanation ne = new NodeExplanation();
            ne.setEvidence(p.getEvidence());
            ne.setAssumptions(p.getAssumptions());
            ne.setCounterexamples(p.getCounterexamples());
            ne.setGeneratedAt(p.getGeneratedAt() == null ? 0L : p.getGeneratedAt());
            ne.setModelName(p.getModelName());
            out.put(p.getNodeId(), ne);
        }
        return out;
    }

    private void insertScenarioNode(String scenarioId, Map<String, Object> n) {
        ScenarioNodePO po = new ScenarioNodePO();
        po.setScenarioId(scenarioId);
        po.setNodeId(asString(n.get("id")));
        po.setLabel(asString(n.get("label")));
        po.setType(asString(n.get("type")));
        po.setSource(asString(n.get("source")));
        po.setX(asDouble(n.get("x")));
        po.setY(asDouble(n.get("y")));
        po.setPredictedStep(asInt(n.get("predictedStep")));
        po.setConfidence(asDouble(n.get("confidence")));
        po.setEffectiveProbability(asDouble(n.get("effectiveProbability")));
        po.setExplanation(asString(n.get("explanation")));
        nodeMapper.insert(po);
    }

    private void insertScenarioEdge(String scenarioId, Map<String, Object> e) {
        ScenarioEdgePO po = new ScenarioEdgePO();
        po.setScenarioId(scenarioId);
        po.setEdgeId(asString(e.get("id")));
        po.setFromNodeId(asString(e.get("from")));
        po.setToNodeId(asString(e.get("to")));
        po.setLabel(asString(e.get("label")));
        po.setSource(asString(e.get("source")));
        Object rd = e.get("rule_driven");
        po.setRuleDriven(rd instanceof Boolean ? (Boolean) rd : Boolean.FALSE);
        po.setRuleId(asString(e.get("ruleId")));
        edgeMapper.insert(po);
    }

    private void insertChainStep(String scenarioId, Map<String, Object> step) {
        ScenarioChainStepPO po = new ScenarioChainStepPO();
        po.setScenarioId(scenarioId);
        po.setStepNo(asInt(step.get("step")));
        po.setNodeId(asString(step.get("nodeId")));
        Object triggeredBy = step.get("triggeredBy");
        po.setTriggeredByJson(triggeredBy == null ? null : codec.toJson(triggeredBy));
        po.setRuleId(asString(step.get("ruleId")));
        po.setExplanation(asString(step.get("explanation")));
        po.setConfidence(asDouble(step.get("confidence")));
        po.setEffectiveProbability(asDouble(step.get("effectiveProbability")));
        po.setCumulativeCredibility(asDouble(step.get("cumulativeCredibility")));
        chainStepMapper.insert(po);
    }

    private void insertConstraint(String scenarioId, Constraint c) {
        ScenarioConstraintPO po = new ScenarioConstraintPO();
        po.setScenarioId(scenarioId);
        po.setNodeId(c.getNodeId());
        po.setMode(c.getMode());
        po.setNote(c.getNote());
        po.setProbability(c.getProbability());
        constraintMapper.insert(po);
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

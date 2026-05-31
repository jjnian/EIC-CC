package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.ScenarioChainStepPO;
import com.tuiyan.backend.entity.ScenarioConstraintPO;
import com.tuiyan.backend.entity.ScenarioEdgePO;
import com.tuiyan.backend.entity.ScenarioNodeExplanationPO;
import com.tuiyan.backend.entity.ScenarioNodePO;
import com.tuiyan.backend.entity.ScenarioSeedPO;
import com.tuiyan.backend.mapper.ScenarioChainStepMapper;
import com.tuiyan.backend.mapper.ScenarioConstraintMapper;
import com.tuiyan.backend.mapper.ScenarioEdgeMapper;
import com.tuiyan.backend.mapper.ScenarioNodeExplanationMapper;
import com.tuiyan.backend.mapper.ScenarioNodeMapper;
import com.tuiyan.backend.mapper.ScenarioSeedMapper;
import com.tuiyan.backend.model.Constraint;
import com.tuiyan.backend.model.NodeExplanation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tuiyan.backend.repository.RepoValueUtils.asDouble;
import static com.tuiyan.backend.repository.RepoValueUtils.asInt;
import static com.tuiyan.backend.repository.RepoValueUtils.asString;

/**
 * 推演分支聚合根的子表行映射协作类：承载 7 张子表（seed/node/edge/chainStep/constraint/explanation）
 * 的 PO↔{@code Map}/Domain 双向行映射与逐行 insert/load。
 * <p>本类只做行映射，不持有事务边界，也不做"覆盖式删除"——这些由 {@link ScenarioRepository} 在其
 * {@code @Transactional} 方法内编排。sortNo 顺序、triggeredBy 的 JSON 拆解契约均在此保持不变。
 */
@Component
public class ScenarioRowMapper {

    private final ScenarioSeedMapper seedMapper;
    private final ScenarioNodeMapper nodeMapper;
    private final ScenarioEdgeMapper edgeMapper;
    private final ScenarioChainStepMapper chainStepMapper;
    private final ScenarioConstraintMapper constraintMapper;
    private final ScenarioNodeExplanationMapper explanationMapper;
    private final JsonCodec codec;

    public ScenarioRowMapper(ScenarioSeedMapper seedMapper,
                             ScenarioNodeMapper nodeMapper,
                             ScenarioEdgeMapper edgeMapper,
                             ScenarioChainStepMapper chainStepMapper,
                             ScenarioConstraintMapper constraintMapper,
                             ScenarioNodeExplanationMapper explanationMapper,
                             ObjectMapper objectMapper) {
        this.seedMapper = seedMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.chainStepMapper = chainStepMapper;
        this.constraintMapper = constraintMapper;
        this.explanationMapper = explanationMapper;
        this.codec = new JsonCodec(objectMapper);
    }

    // ---------- insert（逐表行映射；空集合静默跳过，行为与原内联实现一致） ----------

    /** seeds 按入参顺序写入，sortNo 自增以在回读时还原顺序。 */
    public void insertSeeds(String scenarioId, List<String> seeds) {
        if (seeds == null) return;
        int sortNo = 0;
        for (String seed : seeds) {
            ScenarioSeedPO po = new ScenarioSeedPO();
            po.setScenarioId(scenarioId);
            po.setNodeId(seed);
            po.setSortNo(sortNo++);
            seedMapper.insert(po);
        }
    }

    public void insertNodes(String scenarioId, List<Map<String, Object>> nodes) {
        if (nodes == null) return;
        for (Map<String, Object> n : nodes) {
            insertScenarioNode(scenarioId, n);
        }
    }

    public void insertEdges(String scenarioId, List<Map<String, Object>> edges) {
        if (edges == null) return;
        for (Map<String, Object> e : edges) {
            insertScenarioEdge(scenarioId, e);
        }
    }

    public void insertChainSteps(String scenarioId, List<Map<String, Object>> chain) {
        if (chain == null) return;
        for (Map<String, Object> step : chain) {
            insertChainStep(scenarioId, step);
        }
    }

    public void insertConstraints(String scenarioId, List<Constraint> constraints) {
        if (constraints == null) return;
        for (Constraint c : constraints) {
            insertConstraint(scenarioId, c);
        }
    }

    /** dag.explanations 批量写入：value 为 null 的条目跳过（与原内联实现一致）。 */
    public void insertExplanations(String scenarioId, Map<String, NodeExplanation> explanations) {
        if (explanations == null) return;
        for (Map.Entry<String, NodeExplanation> entry : explanations.entrySet()) {
            NodeExplanation ne = entry.getValue();
            if (ne == null) continue;
            insertExplanation(scenarioId, entry.getKey(), ne);
        }
    }

    /** 单条 explanation 行映射 + insert；供批量写入与 upsert 流式增量写共用。 */
    public void insertExplanation(String scenarioId, String nodeId, NodeExplanation ne) {
        ScenarioNodeExplanationPO po = new ScenarioNodeExplanationPO();
        po.setScenarioId(scenarioId);
        po.setNodeId(nodeId);
        po.setEvidence(ne.getEvidence());
        po.setAssumptions(ne.getAssumptions());
        po.setCounterexamples(ne.getCounterexamples());
        po.setGeneratedAt(ne.getGeneratedAt());
        po.setModelName(ne.getModelName());
        explanationMapper.insert(po);
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

    // ---------- load（逐表行映射） ----------

    /** 按 sortNo 升序还原 seeds 的 nodeId 列表。 */
    public List<String> loadSeeds(String scenarioId) {
        List<ScenarioSeedPO> seeds = seedMapper.selectList(
                new LambdaQueryWrapper<ScenarioSeedPO>()
                        .eq(ScenarioSeedPO::getScenarioId, scenarioId)
                        .orderByAsc(ScenarioSeedPO::getSortNo));
        List<String> seedIds = new ArrayList<>(seeds.size());
        for (ScenarioSeedPO sp : seeds) seedIds.add(sp.getNodeId());
        return seedIds;
    }

    public List<Map<String, Object>> loadNodes(String scenarioId) {
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

    public List<Map<String, Object>> loadEdges(String scenarioId) {
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

    public List<Map<String, Object>> loadChainSteps(String scenarioId) {
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

    public List<Constraint> loadConstraints(String scenarioId) {
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

    public Map<String, NodeExplanation> loadExplanations(String scenarioId) {
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
}

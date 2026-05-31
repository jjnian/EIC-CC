package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.tuiyan.backend.model.NodeExplanation;
import com.tuiyan.backend.model.PredictionDag;
import com.tuiyan.backend.model.Scenario;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 推演分支聚合根仓储：负责 Scenario 与底层 7 张表的读写编排。
 * <p>save 走覆盖式策略：删除该 scenario 下所有子表行后重新插入。
 * <p>子表的逐行 PO↔Map/Domain 映射委派给 {@link ScenarioRowMapper}；本类只保留聚合编排、
 * workspace 校验、级联删除与 {@code @Transactional} 事务边界。
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
    private final ScenarioRowMapper rowMapper;

    public ScenarioRepository(ScenarioMapper scenarioMapper,
                              ScenarioSeedMapper seedMapper,
                              ScenarioNodeMapper nodeMapper,
                              ScenarioEdgeMapper edgeMapper,
                              ScenarioChainStepMapper chainStepMapper,
                              ScenarioConstraintMapper constraintMapper,
                              ScenarioNodeExplanationMapper explanationMapper,
                              ScenarioRowMapper rowMapper) {
        this.scenarioMapper = scenarioMapper;
        this.seedMapper = seedMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.chainStepMapper = chainStepMapper;
        this.constraintMapper = constraintMapper;
        this.explanationMapper = explanationMapper;
        this.rowMapper = rowMapper;
    }

    /** 列出所有分支；modelId 为 null 时返回当前 ws 全部。 */
    public List<Scenario> list(String modelId) {
        LambdaQueryWrapper<ScenarioPO> qw = new LambdaQueryWrapper<>();
        qw.eq(ScenarioPO::getWorkspaceId, WorkspaceContext.required());
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

    /** 按 id 加载单个分支（含完整 dag）；不属于当前 ws 返回 null。 */
    public Scenario get(String id) {
        ScenarioPO po = scenarioMapper.selectById(id);
        if (po == null) return null;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return null;
        return loadScenario(po);
    }

    /**
     * 全量保存（覆盖式）：删旧子表行 → 插新行。
     */
    @Transactional
    public void save(Scenario s) {
        ScenarioPO po = toPO(s);
        ScenarioPO existing = scenarioMapper.selectById(s.getId());
        if (existing == null) {
            po.setWorkspaceId(WorkspaceContext.required());
            scenarioMapper.insert(po);
        } else {
            if (!WorkspaceContext.required().equals(existing.getWorkspaceId())) {
                throw new IllegalArgumentException("Scenario does not belong to current workspace: " + s.getId());
            }
            po.setWorkspaceId(existing.getWorkspaceId());
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
        rowMapper.insertExplanation(scenarioId, nodeId, explanation);
    }

    /**
     * 物理删除分支 + 级联删除所有以该分支为祖先的子分支。
     * @return 实际删除的分支数量（含本身）
     */
    @Transactional
    public int delete(String id) {
        ScenarioPO target = scenarioMapper.selectById(id);
        if (target == null) return 0;
        if (!WorkspaceContext.required().equals(target.getWorkspaceId())) return 0;

        // 收集当前 ws 内以 id 为祖先的所有分支
        List<ScenarioPO> all = scenarioMapper.selectList(
                new LambdaQueryWrapper<ScenarioPO>()
                        .eq(ScenarioPO::getWorkspaceId, WorkspaceContext.required()));
        Map<String, String> parentMap = new HashMap<>();
        for (ScenarioPO p : all) parentMap.put(p.getId(), p.getParentBranchId());

        List<String> toDelete = new ArrayList<>();
        toDelete.add(id);
        for (ScenarioPO s : all) {
            String cur = s.getParentBranchId();
            // visited 防御 parentBranchId 形成环（数据损坏时）导致的死循环
            java.util.Set<String> visited = new java.util.HashSet<>();
            while (cur != null && visited.add(cur)) {
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

    // ---------- 内部编排 ----------

    /** 覆盖式删除：清空该 scenario 下全部子表行。事务边界由调用方 {@code @Transactional} 方法持有。 */
    private void deleteAllChildren(String scenarioId) {
        seedMapper.delete(new LambdaQueryWrapper<ScenarioSeedPO>().eq(ScenarioSeedPO::getScenarioId, scenarioId));
        nodeMapper.delete(new LambdaQueryWrapper<ScenarioNodePO>().eq(ScenarioNodePO::getScenarioId, scenarioId));
        edgeMapper.delete(new LambdaQueryWrapper<ScenarioEdgePO>().eq(ScenarioEdgePO::getScenarioId, scenarioId));
        chainStepMapper.delete(new LambdaQueryWrapper<ScenarioChainStepPO>().eq(ScenarioChainStepPO::getScenarioId, scenarioId));
        constraintMapper.delete(new LambdaQueryWrapper<ScenarioConstraintPO>().eq(ScenarioConstraintPO::getScenarioId, scenarioId));
        explanationMapper.delete(new LambdaQueryWrapper<ScenarioNodeExplanationPO>().eq(ScenarioNodeExplanationPO::getScenarioId, scenarioId));
    }

    /** 插新行：编排各子表写入顺序，逐行映射委派给 {@link ScenarioRowMapper}。 */
    private void insertChildren(Scenario s) {
        rowMapper.insertSeeds(s.getId(), s.getSeeds());
        PredictionDag dag = s.getDag();
        if (dag != null) {
            rowMapper.insertNodes(s.getId(), dag.getNodes());
            rowMapper.insertEdges(s.getId(), dag.getEdges());
            rowMapper.insertChainSteps(s.getId(), dag.getChain());
            rowMapper.insertConstraints(s.getId(), dag.getConstraints());
            rowMapper.insertExplanations(s.getId(), dag.getExplanations());
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

        s.setSeeds(rowMapper.loadSeeds(po.getId()));

        // dag
        PredictionDag dag = new PredictionDag();
        dag.setIntent(po.getIntent());
        dag.setNodes(rowMapper.loadNodes(po.getId()));
        dag.setEdges(rowMapper.loadEdges(po.getId()));
        dag.setChain(rowMapper.loadChainSteps(po.getId()));
        dag.setConstraints(rowMapper.loadConstraints(po.getId()));
        dag.setExplanations(rowMapper.loadExplanations(po.getId()));
        s.setDag(dag);
        return s;
    }
}

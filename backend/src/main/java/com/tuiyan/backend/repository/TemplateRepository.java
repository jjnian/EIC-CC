package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.GraphTemplatePO;
import com.tuiyan.backend.entity.HypothesisTemplatePO;
import com.tuiyan.backend.mapper.GraphTemplateMapper;
import com.tuiyan.backend.mapper.HypothesisTemplateMapper;
import com.tuiyan.backend.model.Constraint;
import com.tuiyan.backend.model.HypothesisTemplate;
import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板仓储：图谱模板 + 假设模板。
 * <p>模板内容（nodes/edges/seeds/constraints）整体序列化为 JSON 字符串存储。
 */
@Repository
public class TemplateRepository {

    private final GraphTemplateMapper graphTemplateMapper;
    private final HypothesisTemplateMapper hypothesisTemplateMapper;
    private final JsonCodec codec;

    public TemplateRepository(GraphTemplateMapper graphTemplateMapper,
                              HypothesisTemplateMapper hypothesisTemplateMapper,
                              ObjectMapper objectMapper) {
        this.graphTemplateMapper = graphTemplateMapper;
        this.hypothesisTemplateMapper = hypothesisTemplateMapper;
        this.codec = new JsonCodec(objectMapper);
    }

    // ---------- 图谱模板 ----------

    public List<OntologyModel> listGraph() {
        List<GraphTemplatePO> pos = graphTemplateMapper.selectList(
                new LambdaQueryWrapper<GraphTemplatePO>()
                        .eq(GraphTemplatePO::getWorkspaceId, WorkspaceContext.required())
                        .orderByDesc(GraphTemplatePO::getUpdatedAt));
        List<OntologyModel> out = new ArrayList<>(pos.size());
        for (GraphTemplatePO po : pos) {
            out.add(graphPOToModel(po));
        }
        return out;
    }

    public OntologyModel getGraph(String id) {
        GraphTemplatePO po = graphTemplateMapper.selectById(id);
        if (po == null) return null;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return null;
        return graphPOToModel(po);
    }

    @Transactional
    public void saveGraph(OntologyModel m) {
        GraphTemplatePO po = new GraphTemplatePO();
        po.setId(m.getId());
        po.setTitle(m.getTitle());
        po.setDescription(m.getDesc());
        po.setNodesJson(m.getGraphData() == null ? null : codec.toJson(m.getGraphData().getNodes()));
        po.setEdgesJson(m.getGraphData() == null ? null : codec.toJson(m.getGraphData().getEdges()));
        po.setCreatedAt(m.getCreatedAt());
        po.setUpdatedAt(m.getUpdatedAt());
        GraphTemplatePO existing = graphTemplateMapper.selectById(m.getId());
        if (existing == null) {
            po.setWorkspaceId(WorkspaceContext.required());
            graphTemplateMapper.insert(po);
        } else {
            if (!WorkspaceContext.required().equals(existing.getWorkspaceId())) {
                throw new IllegalArgumentException("Graph template does not belong to current workspace: " + m.getId());
            }
            po.setWorkspaceId(existing.getWorkspaceId());
            graphTemplateMapper.updateById(po);
        }
    }

    @Transactional
    public boolean deleteGraph(String id) {
        GraphTemplatePO existing = graphTemplateMapper.selectById(id);
        if (existing == null) return false;
        if (!WorkspaceContext.required().equals(existing.getWorkspaceId())) return false;
        return graphTemplateMapper.deleteById(id) > 0;
    }

    private OntologyModel graphPOToModel(GraphTemplatePO po) {
        OntologyModel m = new OntologyModel();
        m.setId(po.getId());
        m.setTitle(po.getTitle());
        m.setDesc(po.getDescription());
        m.setCreatedAt(po.getCreatedAt() == null ? 0L : po.getCreatedAt());
        m.setUpdatedAt(po.getUpdatedAt() == null ? 0L : po.getUpdatedAt());
        OntologyModel.GraphData g = new OntologyModel.GraphData();
        g.setNodes(codec.readMapList(po.getNodesJson()));
        g.setEdges(codec.readMapList(po.getEdgesJson()));
        m.setGraphData(g);
        return m;
    }

    // ---------- 假设模板 ----------

    public List<HypothesisTemplate> listHypothesis(String modelId) {
        LambdaQueryWrapper<HypothesisTemplatePO> qw = new LambdaQueryWrapper<>();
        qw.eq(HypothesisTemplatePO::getWorkspaceId, WorkspaceContext.required());
        if (modelId != null && !modelId.isBlank()) {
            qw.eq(HypothesisTemplatePO::getModelId, modelId);
        }
        qw.orderByDesc(HypothesisTemplatePO::getLastUsedAt);
        List<HypothesisTemplatePO> pos = hypothesisTemplateMapper.selectList(qw);
        List<HypothesisTemplate> out = new ArrayList<>(pos.size());
        for (HypothesisTemplatePO po : pos) {
            out.add(hypothesisPOToModel(po));
        }
        return out;
    }

    public HypothesisTemplate getHypothesis(String id) {
        HypothesisTemplatePO po = hypothesisTemplateMapper.selectById(id);
        if (po == null) return null;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return null;
        return hypothesisPOToModel(po);
    }

    @Transactional
    public void saveHypothesis(HypothesisTemplate t) {
        HypothesisTemplatePO po = new HypothesisTemplatePO();
        po.setId(t.getId());
        po.setModelId(t.getModelId());
        po.setName(t.getName());
        po.setIntent(t.getIntent());
        po.setSteps(t.getSteps());
        po.setPrompt(t.getPrompt());
        po.setSeedsJson(codec.toJson(t.getSeeds()));
        po.setConstraintsJson(codec.toJson(t.getConstraints()));
        po.setCreatedAt(t.getCreatedAt());
        po.setLastUsedAt(t.getLastUsedAt());
        HypothesisTemplatePO existing = hypothesisTemplateMapper.selectById(t.getId());
        if (existing == null) {
            po.setWorkspaceId(WorkspaceContext.required());
            hypothesisTemplateMapper.insert(po);
        } else {
            if (!WorkspaceContext.required().equals(existing.getWorkspaceId())) {
                throw new IllegalArgumentException("Hypothesis template does not belong to current workspace: " + t.getId());
            }
            po.setWorkspaceId(existing.getWorkspaceId());
            hypothesisTemplateMapper.updateById(po);
        }
    }

    @Transactional
    public boolean deleteHypothesis(String id) {
        HypothesisTemplatePO existing = hypothesisTemplateMapper.selectById(id);
        if (existing == null) return false;
        if (!WorkspaceContext.required().equals(existing.getWorkspaceId())) return false;
        return hypothesisTemplateMapper.deleteById(id) > 0;
    }

    /** 仅刷新 last_used_at 字段。 */
    @Transactional
    public boolean touchHypothesis(String id) {
        HypothesisTemplatePO po = hypothesisTemplateMapper.selectById(id);
        if (po == null) return false;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return false;
        po.setLastUsedAt(System.currentTimeMillis());
        hypothesisTemplateMapper.updateById(po);
        return true;
    }

    private HypothesisTemplate hypothesisPOToModel(HypothesisTemplatePO po) {
        HypothesisTemplate t = new HypothesisTemplate();
        t.setId(po.getId());
        t.setModelId(po.getModelId());
        t.setName(po.getName());
        t.setIntent(po.getIntent());
        t.setSteps(po.getSteps() == null ? 0 : po.getSteps());
        t.setPrompt(po.getPrompt());
        t.setSeeds(codec.readStringList(po.getSeedsJson()));
        t.setConstraints(codec.readList(po.getConstraintsJson(), Constraint.class));
        t.setCreatedAt(po.getCreatedAt() == null ? 0L : po.getCreatedAt());
        t.setLastUsedAt(po.getLastUsedAt() == null ? 0L : po.getLastUsedAt());
        return t;
    }
}

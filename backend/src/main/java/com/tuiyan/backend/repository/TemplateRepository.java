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
                new LambdaQueryWrapper<GraphTemplatePO>().orderByDesc(GraphTemplatePO::getUpdatedAt));
        List<OntologyModel> out = new ArrayList<>(pos.size());
        for (GraphTemplatePO po : pos) {
            out.add(graphPOToModel(po));
        }
        return out;
    }

    public OntologyModel getGraph(String id) {
        GraphTemplatePO po = graphTemplateMapper.selectById(id);
        return po == null ? null : graphPOToModel(po);
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
        if (graphTemplateMapper.selectById(m.getId()) == null) {
            graphTemplateMapper.insert(po);
        } else {
            graphTemplateMapper.updateById(po);
        }
    }

    @Transactional
    public boolean deleteGraph(String id) {
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
        return po == null ? null : hypothesisPOToModel(po);
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
        if (hypothesisTemplateMapper.selectById(t.getId()) == null) {
            hypothesisTemplateMapper.insert(po);
        } else {
            hypothesisTemplateMapper.updateById(po);
        }
    }

    @Transactional
    public boolean deleteHypothesis(String id) {
        return hypothesisTemplateMapper.deleteById(id) > 0;
    }

    /** 仅刷新 last_used_at 字段。 */
    @Transactional
    public boolean touchHypothesis(String id) {
        HypothesisTemplatePO po = hypothesisTemplateMapper.selectById(id);
        if (po == null) return false;
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

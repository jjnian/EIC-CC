package com.tuiyan.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.entity.ConversationPO;
import com.tuiyan.backend.entity.GraphTemplatePO;
import com.tuiyan.backend.entity.HypothesisTemplatePO;
import com.tuiyan.backend.entity.OntologyModelPO;
import com.tuiyan.backend.entity.ScenarioPO;
import com.tuiyan.backend.mapper.ConversationMapper;
import com.tuiyan.backend.mapper.GraphTemplateMapper;
import com.tuiyan.backend.mapper.HypothesisTemplateMapper;
import com.tuiyan.backend.mapper.OntologyModelMapper;
import com.tuiyan.backend.mapper.ScenarioMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 删除工作空间时的级联清理器：把"知道 ws 下有哪些业务数据、如何清理"这件事从
 * {@link WorkspaceService} 里独立出来，让 WorkspaceService 只管 CRUD 编排。
 * <p>仅清理工作空间私有数据（本体模型 / 场景 / 对话 / 模板）；数据源与经验库为全局公共资源，
 * 不随创建它的工作空间删除（详见 {@link #cleanup}）。
 */
@Service
public class WorkspaceCascadeCleaner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCascadeCleaner.class);

    private final OntologyModelMapper ontologyModelMapper;
    private final ScenarioMapper scenarioMapper;
    private final ConversationMapper conversationMapper;
    private final GraphTemplateMapper graphTemplateMapper;
    private final HypothesisTemplateMapper hypothesisTemplateMapper;

    public WorkspaceCascadeCleaner(OntologyModelMapper ontologyModelMapper,
                                   ScenarioMapper scenarioMapper,
                                   ConversationMapper conversationMapper,
                                   GraphTemplateMapper graphTemplateMapper,
                                   HypothesisTemplateMapper hypothesisTemplateMapper) {
        this.ontologyModelMapper = ontologyModelMapper;
        this.scenarioMapper = scenarioMapper;
        this.conversationMapper = conversationMapper;
        this.graphTemplateMapper = graphTemplateMapper;
        this.hypothesisTemplateMapper = hypothesisTemplateMapper;
    }

    /**
     * 清空指定工作空间下的业务数据与副作用。调用方负责事务边界与 workspace 主记录的删除。
     * <p>注意：数据源与经验库已是<strong>全局公共资源</strong>，不随创建它的工作空间一并删除——
     * 仅保留其归属标签（创建于该工作空间，删除后展示为「已删除」），其余工作空间仍可继续引用与使用。
     */
    public void cleanup(String workspaceId) {
        int models = ontologyModelMapper.delete(
                new LambdaQueryWrapper<OntologyModelPO>().eq(OntologyModelPO::getWorkspaceId, workspaceId));
        int scenarios = scenarioMapper.delete(
                new LambdaQueryWrapper<ScenarioPO>().eq(ScenarioPO::getWorkspaceId, workspaceId));
        int convs = conversationMapper.delete(
                new LambdaQueryWrapper<ConversationPO>().eq(ConversationPO::getWorkspaceId, workspaceId));
        int graphTpls = graphTemplateMapper.delete(
                new LambdaQueryWrapper<GraphTemplatePO>().eq(GraphTemplatePO::getWorkspaceId, workspaceId));
        int hypTpls = hypothesisTemplateMapper.delete(
                new LambdaQueryWrapper<HypothesisTemplatePO>().eq(HypothesisTemplatePO::getWorkspaceId, workspaceId));
        log.info("delete workspace {}: models={}, scenarios={}, conversations={}, graphTpls={}, hypTpls={}（数据源/经验为公共资源，保留）",
                workspaceId, models, scenarios, convs, graphTpls, hypTpls);
    }
}

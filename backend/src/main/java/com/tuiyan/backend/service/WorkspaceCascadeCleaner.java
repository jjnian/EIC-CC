package com.tuiyan.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.entity.ConversationPO;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.entity.ExperiencePO;
import com.tuiyan.backend.entity.GraphTemplatePO;
import com.tuiyan.backend.entity.HypothesisTemplatePO;
import com.tuiyan.backend.entity.OntologyModelPO;
import com.tuiyan.backend.entity.ScenarioPO;
import com.tuiyan.backend.mapper.ConversationMapper;
import com.tuiyan.backend.mapper.DataSourceMapper;
import com.tuiyan.backend.mapper.ExperienceMapper;
import com.tuiyan.backend.mapper.GraphTemplateMapper;
import com.tuiyan.backend.mapper.HypothesisTemplateMapper;
import com.tuiyan.backend.mapper.OntologyModelMapper;
import com.tuiyan.backend.mapper.ScenarioMapper;
import com.tuiyan.backend.service.connector.FileStoredService;
import com.tuiyan.backend.service.connector.HttpScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 删除工作空间时的级联清理器：把"知道 ws 下有哪些业务数据、如何清理副作用"这件事从
 * {@link WorkspaceService} 里独立出来，让 WorkspaceService 只管 CRUD 编排。
 * <p>清理顺序：先取消 https_api 数据源的定时任务、删除 file_stored 物理目录（副作用，失败仅记日志），
 * 再 DELETE 主体表（由外键 ON DELETE CASCADE 自动清理子表）。
 */
@Service
public class WorkspaceCascadeCleaner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCascadeCleaner.class);

    private final OntologyModelMapper ontologyModelMapper;
    private final ScenarioMapper scenarioMapper;
    private final ConversationMapper conversationMapper;
    private final GraphTemplateMapper graphTemplateMapper;
    private final HypothesisTemplateMapper hypothesisTemplateMapper;
    private final DataSourceMapper dataSourceMapper;
    private final ExperienceMapper experienceMapper;
    private final FileStoredService fileStoredService;
    private final HttpScheduler httpScheduler;

    public WorkspaceCascadeCleaner(OntologyModelMapper ontologyModelMapper,
                                   ScenarioMapper scenarioMapper,
                                   ConversationMapper conversationMapper,
                                   GraphTemplateMapper graphTemplateMapper,
                                   HypothesisTemplateMapper hypothesisTemplateMapper,
                                   DataSourceMapper dataSourceMapper,
                                   ExperienceMapper experienceMapper,
                                   FileStoredService fileStoredService,
                                   HttpScheduler httpScheduler) {
        this.ontologyModelMapper = ontologyModelMapper;
        this.scenarioMapper = scenarioMapper;
        this.conversationMapper = conversationMapper;
        this.graphTemplateMapper = graphTemplateMapper;
        this.hypothesisTemplateMapper = hypothesisTemplateMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.experienceMapper = experienceMapper;
        this.fileStoredService = fileStoredService;
        this.httpScheduler = httpScheduler;
    }

    /** 清空指定工作空间下的全部业务数据与副作用。调用方负责事务边界与 workspace 主记录的删除。 */
    public void cleanup(String workspaceId) {
        cancelDataSourceSideEffects(workspaceId);

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
        int dataSources = dataSourceMapper.delete(
                new LambdaQueryWrapper<DataSourcePO>().eq(DataSourcePO::getWorkspaceId, workspaceId));
        int experiences = experienceMapper.delete(
                new LambdaQueryWrapper<ExperiencePO>().eq(ExperiencePO::getWorkspaceId, workspaceId));
        log.info("delete workspace {}: models={}, scenarios={}, conversations={}, graphTpls={}, hypTpls={}, dataSources={}, experiences={}",
                workspaceId, models, scenarios, convs, graphTpls, hypTpls, dataSources, experiences);
    }

    /** 取消该 ws 下 https_api 数据源的定时任务、删除 file_stored 的物理目录；逐个 try-catch，失败仅记日志。 */
    private void cancelDataSourceSideEffects(String workspaceId) {
        List<DataSourcePO> dsList = dataSourceMapper.selectList(
                new LambdaQueryWrapper<DataSourcePO>().eq(DataSourcePO::getWorkspaceId, workspaceId));
        for (DataSourcePO ds : dsList) {
            try {
                if ("https_api".equals(ds.getKind())) {
                    httpScheduler.cancel(ds.getId());
                } else if ("file_stored".equals(ds.getKind())) {
                    fileStoredService.deleteFiles(ds.getId());
                }
            } catch (Exception e) {
                log.warn("cleanup datasource {} failed: {}", ds.getId(), e.toString());
            }
        }
    }
}

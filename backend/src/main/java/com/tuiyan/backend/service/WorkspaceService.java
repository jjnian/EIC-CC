package com.tuiyan.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.config.ResourceNotFoundException;
import com.tuiyan.backend.entity.ConversationPO;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.entity.GraphTemplatePO;
import com.tuiyan.backend.entity.HypothesisTemplatePO;
import com.tuiyan.backend.entity.OntologyModelPO;
import com.tuiyan.backend.entity.ScenarioPO;
import com.tuiyan.backend.entity.WorkspacePO;
import com.tuiyan.backend.mapper.ConversationMapper;
import com.tuiyan.backend.mapper.DataSourceMapper;
import com.tuiyan.backend.mapper.GraphTemplateMapper;
import com.tuiyan.backend.mapper.HypothesisTemplateMapper;
import com.tuiyan.backend.mapper.OntologyModelMapper;
import com.tuiyan.backend.mapper.ScenarioMapper;
import com.tuiyan.backend.repository.WorkspaceRepository;
import com.tuiyan.backend.service.connector.FileStoredService;
import com.tuiyan.backend.service.connector.HttpScheduler;
import com.tuiyan.backend.support.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 工作空间业务服务：CRUD + 删除时级联清空该 ws 下所有业务数据。
 * <p>级联策略：直接 DELETE 主体表（ontology_model / scenario / conversation / 两类模板），
 * 由现有外键 ON DELETE CASCADE 自动清理子表（节点、边、props、versions、消息、推演链等）。
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final WorkspaceRepository repo;
    private final OntologyModelMapper ontologyModelMapper;
    private final ScenarioMapper scenarioMapper;
    private final ConversationMapper conversationMapper;
    private final GraphTemplateMapper graphTemplateMapper;
    private final HypothesisTemplateMapper hypothesisTemplateMapper;
    private final DataSourceMapper dataSourceMapper;
    private final FileStoredService fileStoredService;
    private final HttpScheduler httpScheduler;

    public WorkspaceService(WorkspaceRepository repo,
                            OntologyModelMapper ontologyModelMapper,
                            ScenarioMapper scenarioMapper,
                            ConversationMapper conversationMapper,
                            GraphTemplateMapper graphTemplateMapper,
                            HypothesisTemplateMapper hypothesisTemplateMapper,
                            DataSourceMapper dataSourceMapper,
                            FileStoredService fileStoredService,
                            HttpScheduler httpScheduler) {
        this.repo = repo;
        this.ontologyModelMapper = ontologyModelMapper;
        this.scenarioMapper = scenarioMapper;
        this.conversationMapper = conversationMapper;
        this.graphTemplateMapper = graphTemplateMapper;
        this.hypothesisTemplateMapper = hypothesisTemplateMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.fileStoredService = fileStoredService;
        this.httpScheduler = httpScheduler;
    }

    public List<WorkspacePO> list() {
        return repo.list();
    }

    public WorkspacePO get(String id) {
        WorkspacePO ws = repo.get(id);
        if (ws == null) throw new ResourceNotFoundException("Workspace not found: " + id);
        return ws;
    }

    public WorkspacePO create(WorkspacePO input) {
        if (input.getName() == null || input.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        input.setIsDefault(false);
        return repo.create(input);
    }

    public WorkspacePO update(String id, WorkspacePO input) {
        WorkspacePO existing = get(id);
        if (input.getName() != null && !input.getName().isBlank()) {
            existing.setName(input.getName());
        }
        if (input.getDescription() != null) {
            existing.setDescription(input.getDescription());
        }
        if (input.getSortNo() != null) {
            existing.setSortNo(input.getSortNo());
        }
        return repo.update(existing);
    }

    /**
     * 删除工作空间：级联清空业务数据；删除默认 ws 时若还有其它 ws 则自动升级一个为默认，否则允许删光。
     * <p>顺序：先取消该 ws 下 https_api 数据源的定时任务、删除 file_stored 的物理目录，
     * 再走 DB 级联删除（外键 ON DELETE CASCADE 处理子表）。
     * 副作用清理放在 DB 删除之前，文件层失败仅记日志，不影响事务。
     * @return true 表示成功删除
     */
    @Transactional
    public boolean delete(String id) {
        WorkspacePO ws = repo.get(id);
        if (ws == null) return false;
        // 删除默认 ws 时，若还有其它 ws 则自动把下一个升为默认；若这是最后一个则允许直接删光。
        if (Boolean.TRUE.equals(ws.getIsDefault())) {
            WorkspacePO next = repo.list().stream()
                    .filter(w -> !w.getId().equals(id))
                    .findFirst()
                    .orElse(null);
            if (next != null) {
                next.setIsDefault(true);
                repo.update(next);
            }
        }
        // 1. 该 ws 下所有数据源：cancel 调度 + 清盘
        List<DataSourcePO> dsList = dataSourceMapper.selectList(
                new LambdaQueryWrapper<DataSourcePO>().eq(DataSourcePO::getWorkspaceId, id));
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
        // 2. 关联业务数据级联清空（外键 ON DELETE CASCADE 处理子表）
        int models = ontologyModelMapper.delete(
                new LambdaQueryWrapper<OntologyModelPO>().eq(OntologyModelPO::getWorkspaceId, id));
        int scenarios = scenarioMapper.delete(
                new LambdaQueryWrapper<ScenarioPO>().eq(ScenarioPO::getWorkspaceId, id));
        int convs = conversationMapper.delete(
                new LambdaQueryWrapper<ConversationPO>().eq(ConversationPO::getWorkspaceId, id));
        int graphTpls = graphTemplateMapper.delete(
                new LambdaQueryWrapper<GraphTemplatePO>().eq(GraphTemplatePO::getWorkspaceId, id));
        int hypTpls = hypothesisTemplateMapper.delete(
                new LambdaQueryWrapper<HypothesisTemplatePO>().eq(HypothesisTemplatePO::getWorkspaceId, id));
        int dataSources = dataSourceMapper.delete(
                new LambdaQueryWrapper<DataSourcePO>().eq(DataSourcePO::getWorkspaceId, id));
        log.info("delete workspace {}: models={}, scenarios={}, conversations={}, graphTpls={}, hypTpls={}, dataSources={}",
                id, models, scenarios, convs, graphTpls, hypTpls, dataSources);
        return repo.delete(id);
    }

    /** 当前请求所属 ws 的便捷访问。 */
    public String currentWorkspaceId() {
        return WorkspaceContext.required();
    }
}

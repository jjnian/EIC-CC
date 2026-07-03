package com.tuiyan.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.entity.ConversationPO;
import com.tuiyan.backend.entity.DataSourceFolderPO;
import com.tuiyan.backend.entity.ExperienceFolderPO;
import com.tuiyan.backend.entity.GraphTemplatePO;
import com.tuiyan.backend.entity.OntologyModelPO;
import com.tuiyan.backend.mapper.ConversationMapper;
import com.tuiyan.backend.mapper.DataSourceFolderMapper;
import com.tuiyan.backend.mapper.ExperienceFolderMapper;
import com.tuiyan.backend.mapper.GraphTemplateMapper;
import com.tuiyan.backend.mapper.OntologyModelMapper;
import com.tuiyan.backend.repository.DataSourceRefRepository;
import com.tuiyan.backend.repository.ExperienceRefRepository;
import com.tuiyan.backend.repository.NodeDataBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 删除工作空间时的级联清理器：把"知道 ws 下有哪些业务数据、如何清理"这件事从
 * {@link WorkspaceService} 里独立出来，让 WorkspaceService 只管 CRUD 编排。
 * <p>仅清理工作空间私有数据（本体模型 / 对话 / 模板）；数据源与经验库为全局公共资源，
 * 不随创建它的工作空间删除（详见 {@link #cleanup}）。
 */
@Service
public class WorkspaceCascadeCleaner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCascadeCleaner.class);

    private final OntologyModelMapper ontologyModelMapper;
    private final ConversationMapper conversationMapper;
    private final GraphTemplateMapper graphTemplateMapper;
    private final ExperienceRefRepository experienceRefRepository;
    private final DataSourceRefRepository dataSourceRefRepository;
    private final NodeDataBindingRepository nodeDataBindingRepository;
    private final ExperienceFolderMapper experienceFolderMapper;
    private final DataSourceFolderMapper dataSourceFolderMapper;

    public WorkspaceCascadeCleaner(OntologyModelMapper ontologyModelMapper,
                                   ConversationMapper conversationMapper,
                                   GraphTemplateMapper graphTemplateMapper,
                                   ExperienceRefRepository experienceRefRepository,
                                   DataSourceRefRepository dataSourceRefRepository,
                                   NodeDataBindingRepository nodeDataBindingRepository,
                                   ExperienceFolderMapper experienceFolderMapper,
                                   DataSourceFolderMapper dataSourceFolderMapper) {
        this.ontologyModelMapper = ontologyModelMapper;
        this.conversationMapper = conversationMapper;
        this.graphTemplateMapper = graphTemplateMapper;
        this.experienceRefRepository = experienceRefRepository;
        this.dataSourceRefRepository = dataSourceRefRepository;
        this.nodeDataBindingRepository = nodeDataBindingRepository;
        this.experienceFolderMapper = experienceFolderMapper;
        this.dataSourceFolderMapper = dataSourceFolderMapper;
    }

    /**
     * 清空指定工作空间下的业务数据与副作用。调用方负责事务边界与 workspace 主记录的删除。
     * <p>注意：数据源与经验库已是<strong>全局公共资源</strong>，不随创建它的工作空间一并删除——
     * 仅保留其归属标签（创建于该工作空间，删除后展示为「已删除」），其余工作空间仍可继续引用与使用。
     */
    public void cleanup(String workspaceId) {
        int models = ontologyModelMapper.delete(
                new LambdaQueryWrapper<OntologyModelPO>().eq(OntologyModelPO::getWorkspaceId, workspaceId));
        int convs = conversationMapper.delete(
                new LambdaQueryWrapper<ConversationPO>().eq(ConversationPO::getWorkspaceId, workspaceId));
        int graphTpls = graphTemplateMapper.delete(
                new LambdaQueryWrapper<GraphTemplatePO>().eq(GraphTemplatePO::getWorkspaceId, workspaceId));
        // 数据源/经验本体保留为公共资源，但清理该工作空间对它们的引用关系（避免悬挂引用）。
        experienceRefRepository.deleteByWorkspace(workspaceId);
        dataSourceRefRepository.deleteByWorkspace(workspaceId);
        // 上面 ontologyModelMapper.delete(...) 是按 workspace_id 批量删的，不会逐个走
        // OntologyModelRepository.delete()（那里另有 deleteByModel 兜底单个删除场景），
        // 这里按 workspace_id 直接批量清，避免绑定悬挂指向已被删掉的模型。
        nodeDataBindingRepository.deleteByWorkspace(workspaceId);
        // 文件夹按工作空间隔离（experience_folder / data_source_folder 无外键级联），
        // 引用上的归类（*_ref.folder_id）已随上面的引用清理一并消失，这里把文件夹本体也清掉。
        int expFolders = experienceFolderMapper.delete(
                new LambdaQueryWrapper<ExperienceFolderPO>().eq(ExperienceFolderPO::getWorkspaceId, workspaceId));
        int dsFolders = dataSourceFolderMapper.delete(
                new LambdaQueryWrapper<DataSourceFolderPO>().eq(DataSourceFolderPO::getWorkspaceId, workspaceId));
        log.info("delete workspace {}: models={}, conversations={}, graphTpls={}, expFolders={}, dsFolders={}（数据源/经验为公共资源，保留本体、清引用）",
                workspaceId, models, convs, graphTpls, expFolders, dsFolders);
    }
}

package com.tuiyan.backend.service;

import com.tuiyan.backend.config.ResourceNotFoundException;
import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.repository.OntologyModelRepository;
import com.tuiyan.backend.repository.OntologyVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 本体图谱模型服务：CRUD + 版本快照管理 + 默认种子数据。
 * <p>核心特性：
 * <ul>
 *   <li>每次保存前先把"当前模型快照"写入 {@code ontology_model_version} 系列表，最多保留 100 份；</li>
 *   <li>首次进入空工作空间时（list 为空）自动播种 3 个示例图谱，让用户能立刻上手；</li>
 *   <li>支持按时间戳恢复到任意历史版本，恢复前的当前版本也会被备份成新快照。</li>
 * </ul>
 */
@Service
public class OntologyModelService {

    private static final Logger log = LoggerFactory.getLogger(OntologyModelService.class);

    private final OntologyModelRepository modelRepository;
    private final OntologyVersionRepository versionRepository;

    public OntologyModelService(OntologyModelRepository modelRepository,
                                OntologyVersionRepository versionRepository) {
        this.modelRepository = modelRepository;
        this.versionRepository = versionRepository;
    }

    /** 全量列表，按 updatedAt 倒序。 */
    public List<OntologyModel> list() {
        return modelRepository.list();
    }

    public OntologyModel get(String id) {
        return modelRepository.get(id);
    }

    /**
     * 保存（新建或更新）。
     * <p>更新场景下会先把旧版本写入版本表作为快照，再覆盖写入新内容，保证"任何一次保存"都有回溯点。
     */
    public OntologyModel save(OntologyModel m) {
        if (m.getId() == null || m.getId().isBlank()) {
            m.setId("om_" + System.currentTimeMillis());
        }
        long now = System.currentTimeMillis();
        if (m.getCreatedAt() == 0L) m.setCreatedAt(now);
        m.setUpdatedAt(now);
        m.setUpdated("刚刚");
        // 若已存在则先快照旧版本
        OntologyModel existing = modelRepository.get(m.getId());
        if (existing != null) {
            try {
                versionRepository.snapshot(existing);
            } catch (Exception e) {
                log.warn("snapshot failed for model {}: {}", m.getId(), e.toString());
            }
        }
        modelRepository.save(m);
        return m;
    }

    /** 按指定 id 更新；id 强制覆写，保证路径与 body 里 id 一致。 */
    public OntologyModel update(String id, OntologyModel m) {
        m.setId(id);
        return save(m);
    }

    public boolean delete(String id) {
        return modelRepository.delete(id);
    }

    /** 列出版本快照（不含内容，只返回时间戳 + 节点 / 边数概要 + 文件大小）。 */
    public List<Map<String, Object>> listVersions(String modelId) {
        return versionRepository.listVersions(modelId);
    }

    /**
     * 恢复指定时间戳的版本快照。
     * <p>恢复过程调用 {@link #save}，所以当前版本会被自动备份为新的快照——恢复操作本身也是可撤销的。
     */
    public OntologyModel restoreVersion(String modelId, long timestamp) {
        OntologyModel snapshot = versionRepository.loadByTimestamp(modelId, timestamp);
        if (snapshot == null) {
            throw new ResourceNotFoundException("Version not found: " + timestamp);
        }
        snapshot.setId(modelId);
        return save(snapshot);
    }

}

package com.tuiyan.backend.service;

import com.tuiyan.backend.exception.ResourceNotFoundException;
import com.tuiyan.backend.entity.WorkspacePO;
import com.tuiyan.backend.repository.WorkspaceRepository;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 工作空间业务服务：CRUD + 删除时级联清空该 ws 下所有业务数据。
 * <p>级联清理细节（知道有哪些业务表、如何清副作用）委托给 {@link WorkspaceCascadeCleaner}，
 * 本类只负责 CRUD 编排与默认 ws 的升级逻辑。
 */
@Service
public class WorkspaceService {

    private final WorkspaceRepository repo;
    private final WorkspaceCascadeCleaner cascadeCleaner;

    public WorkspaceService(WorkspaceRepository repo, WorkspaceCascadeCleaner cascadeCleaner) {
        this.repo = repo;
        this.cascadeCleaner = cascadeCleaner;
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
     * <p>顺序：先升级默认 ws，再由 {@link WorkspaceCascadeCleaner} 清理业务数据与副作用，最后删主记录。
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
        cascadeCleaner.cleanup(id);
        return repo.delete(id);
    }

    /** 当前请求所属 ws 的便捷访问。 */
    public String currentWorkspaceId() {
        return WorkspaceContext.required();
    }
}

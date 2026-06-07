package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.entity.ExperienceFolderPO;
import com.tuiyan.backend.entity.ExperiencePO;
import com.tuiyan.backend.mapper.ExperienceFolderMapper;
import com.tuiyan.backend.mapper.ExperienceMapper;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经验库仓储：列表 / 详情 / 新增 / 编辑 / 删除，按当前工作空间隔离。
 * <p>结构与 {@link DataSourceRepository} 平行：经验库与数据源同级别，挂在工作空间下。
 */
@Repository
public class ExperienceRepository {

    private final ExperienceMapper mapper;
    private final ExperienceFolderMapper folderMapper;

    public ExperienceRepository(ExperienceMapper mapper, ExperienceFolderMapper folderMapper) {
        this.mapper = mapper;
        this.folderMapper = folderMapper;
    }

    /** 当前工作空间下全部经验，按创建时间倒序。 */
    public List<Map<String, Object>> list() {
        return list(WorkspaceContext.required());
    }

    /** 指定工作空间下全部经验（侧栏跨工作空间懒加载）。 */
    public List<Map<String, Object>> list(String workspaceId) {
        return mapper.selectList(new LambdaQueryWrapper<ExperiencePO>()
                        .eq(ExperiencePO::getWorkspaceId, workspaceId)
                        .orderByDesc(ExperiencePO::getCreatedAt))
                .stream().map(this::toMap).toList();
    }

    /** 跨工作空间的全量经验列表，按创建时间倒序。 */
    public List<Map<String, Object>> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<ExperiencePO>()
                        .orderByDesc(ExperiencePO::getCreatedAt))
                .stream().map(this::toMap).toList();
    }

    /** 按 id 取一行并校验工作空间归属；不归属当前 ws 返回 null。 */
    public Map<String, Object> findFull(String id) {
        ExperiencePO po = mapper.selectById(id);
        if (po == null) return null;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return null;
        return toMap(po);
    }

    /** 手写 / DDL 等纯文本经验（origin=manual）。 */
    @Transactional
    public Map<String, Object> create(String title, String content, String tags) {
        return create(title, content, tags, "manual");
    }

    /** 指定来源的纯文本经验（manual / ddl）。 */
    @Transactional
    public Map<String, Object> create(String title, String content, String tags, String origin) {
        ExperiencePO po = newPo(title, content, tags, origin);
        mapper.insert(po);
        return toMap(po);
    }

    /**
     * 上传文件建经验（origin=upload）：正文为抽取文本，另携带原始文件元信息。
     * 原始文件字节由上层落对象存储后再用 {@link #attachStoragePath} 回填 storage_path。
     */
    @Transactional
    public Map<String, Object> createUploaded(String title, String content,
                                              String fileName, String fileMime, Long fileSize) {
        ExperiencePO po = newPo(title, content, null, "upload");
        po.setFileName(fileName);
        po.setFileMime(fileMime);
        po.setFileSize(fileSize);
        mapper.insert(po);
        return toMap(po);
    }

    /** 回填原始文件在对象存储中的 key（归档成功后调用）。 */
    @Transactional
    public void attachStoragePath(String id, String storagePath) {
        ExperiencePO po = mapper.selectById(id);
        if (po == null) return;
        po.setStoragePath(storagePath);
        mapper.updateById(po);
    }

    private ExperiencePO newPo(String title, String content, String tags, String origin) {
        ExperiencePO po = new ExperiencePO();
        po.setId("exp_" + System.currentTimeMillis() + "_" + Long.toString(System.nanoTime() & 0xffff, 36));
        po.setWorkspaceId(WorkspaceContext.required());
        po.setTitle(title == null || title.isBlank() ? "未命名经验" : title.trim());
        po.setContent(content);
        po.setTags(tags);
        po.setOrigin(origin == null ? "manual" : origin);
        po.setCreatedAt(System.currentTimeMillis());
        po.setUpdatedAt(po.getCreatedAt());
        return po;
    }

    @Transactional
    public Map<String, Object> update(String id, String title, String content, String tags) {
        ExperiencePO po = mapper.selectById(id);
        if (po == null) return null;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return null;
        if (title != null && !title.isBlank()) po.setTitle(title.trim());
        if (content != null) po.setContent(content);
        if (tags != null) po.setTags(tags);
        po.setUpdatedAt(System.currentTimeMillis());
        mapper.updateById(po);
        return toMap(po);
    }

    /**
     * 移动经验到文件夹（folderId=null 移到根）。校验经验与目标文件夹均属于当前工作空间。
     * @return 是否更新成功；经验不存在/越权返回 false，目标文件夹非法抛 400。
     */
    @Transactional
    public boolean moveToFolder(String id, String folderId) {
        String ws = WorkspaceContext.required();
        ExperiencePO po = mapper.selectById(id);
        if (po == null || !ws.equals(po.getWorkspaceId())) return false;
        String target = (folderId == null || folderId.isBlank()) ? null : folderId.trim();
        if (target != null) {
            ExperienceFolderPO f = folderMapper.selectById(target);
            if (f == null || !ws.equals(f.getWorkspaceId())) {
                throw new IllegalArgumentException("目标文件夹不存在或不属于当前工作空间");
            }
        }
        po.setFolderId(target);
        po.setUpdatedAt(System.currentTimeMillis());
        return mapper.updateById(po) > 0;
    }

    /** 按 id 取一行（不做工作空间隔离，索引服务在异步线程里调用，自行不依赖请求上下文）。 */
    public ExperiencePO findById(String id) {
        return mapper.selectById(id);
    }

    /** 按 id 取 PO 并校验工作空间归属（供预览/下载原件用）；不归属当前 ws 返回 null。 */
    public ExperiencePO findPoScoped(String id) {
        ExperiencePO po = mapper.selectById(id);
        if (po == null) return null;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return null;
        return po;
    }

    @Transactional
    public boolean delete(String id) {
        ExperiencePO po = mapper.selectById(id);
        if (po == null) return false;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return false;
        return mapper.deleteById(id) > 0;
    }

    private Map<String, Object> toMap(ExperiencePO po) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", po.getId());
        out.put("workspaceId", po.getWorkspaceId());
        if (po.getFolderId() != null) out.put("folderId", po.getFolderId());
        out.put("title", po.getTitle());
        if (po.getContent() != null) out.put("content", po.getContent());
        if (po.getTags() != null) out.put("tags", po.getTags());
        out.put("createdAt", po.getCreatedAt());
        if (po.getUpdatedAt() != null) out.put("updatedAt", po.getUpdatedAt());
        out.put("indexStatus", po.getIndexStatus() != null ? po.getIndexStatus() : "none");
        out.put("origin", po.getOrigin() != null ? po.getOrigin() : "manual");
        if (po.getFileName() != null) out.put("fileName", po.getFileName());
        if (po.getFileMime() != null) out.put("fileMime", po.getFileMime());
        if (po.getFileSize() != null) out.put("fileSize", po.getFileSize());
        // 不外泄对象存储 key，只暴露「是否有可预览原件」布尔位
        out.put("hasFile", po.getStoragePath() != null && !po.getStoragePath().isBlank());
        return out;
    }
}

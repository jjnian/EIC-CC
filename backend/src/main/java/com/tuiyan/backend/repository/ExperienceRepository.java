package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 连接配置里的敏感字段，列表/详情对外一律遮蔽，仅服务端探索时读原文。 */
    private static final String MASK = "********";

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
     * 数据源 DDL 导出经验（origin=ddl）的 upsert：同一数据源重复导出时原地刷新正文,不再堆副本。
     * 以 source_config 里的 dataSourceId 为锚点;旧版(无 source_config)的 DDL 经验按标题完全相同收养。
     * 内容是结构内省的机器快照,刷新即"重新导出"的自然语义。
     */
    @Transactional
    public Map<String, Object> upsertDdl(String dataSourceId, String title, String content, String tags) {
        String ws = WorkspaceContext.required();
        List<ExperiencePO> existing = mapper.selectList(new LambdaQueryWrapper<ExperiencePO>()
                .eq(ExperiencePO::getWorkspaceId, ws)
                .eq(ExperiencePO::getOrigin, "ddl")
                .orderByDesc(ExperiencePO::getCreatedAt));
        ExperiencePO hit = null;
        for (ExperiencePO po : existing) {
            Object anchor = parseConfig(po.getSourceConfig()).get("dataSourceId");
            if (dataSourceId.equals(anchor)) { hit = po; break; }
            // 旧数据兜底:从未带锚点且标题一致的,视为同一数据源的历史导出,收养并补锚点
            if (anchor == null && hit == null && title.equals(po.getTitle())) hit = po;
        }
        if (hit != null) {
            hit.setTitle(title);
            hit.setContent(content);
            if (tags != null) hit.setTags(tags);
            hit.setSourceConfig(toJson(Map.of("dataSourceId", dataSourceId)));
            hit.setUpdatedAt(System.currentTimeMillis());
            mapper.updateById(hit);
            return toMap(hit);
        }
        ExperiencePO po = newPo(title, content, tags, "ddl");
        po.setSourceConfig(toJson(Map.of("dataSourceId", dataSourceId)));
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

    /**
     * 接入 web 系统建经验（origin=websystem）：正文初始为空，连接配置（入口地址/账号/密码/步数等）
     * 以 JSON 存入 source_config。点「探索」时按此配置运行自动探索，每次另产一篇 origin=explore 经验。
     */
    @Transactional
    public Map<String, Object> createWebSystem(String title, Map<String, Object> config) {
        ExperiencePO po = newPo(title, "", "web系统,接入", "websystem");
        po.setSourceConfig(toJson(config));
        mapper.insert(po);
        return toMap(po);
    }

    /**
     * 编辑已接入 web 系统的连接配置（按工作空间隔离）。title 为空则不改；config 为 null 则不动连接配置。
     * @return 更新后的经验 map；不存在/越权返回 null。
     */
    @Transactional
    public Map<String, Object> updateWebSystem(String id, String title, Map<String, Object> config) {
        ExperiencePO po = mapper.selectById(id);
        if (po == null) return null;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return null;
        if (title != null && !title.isBlank()) po.setTitle(title.trim());
        if (config != null) po.setSourceConfig(toJson(config));
        po.setUpdatedAt(System.currentTimeMillis());
        mapper.updateById(po);
        return toMap(po);
    }

    /**
     * 读取 web 系统连接配置原文（含真实密码），按工作空间隔离 —— 仅供服务端探索时调用，
     * 绝不经此把密码回传前端。不存在/越权/非 websystem 返回 null。
     */
    public Map<String, Object> readSourceConfigScoped(String id) {
        ExperiencePO po = mapper.selectById(id);
        if (po == null) return null;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return null;
        return parseConfig(po.getSourceConfig());
    }

    /**
     * 增量合并 web 系统连接配置（按工作空间隔离,仅 origin=websystem 生效）。
     * 用于探索时把登录成功后的 storageState 等回存进配置,patch 中的键覆盖同名旧值,其余保留。
     * @return 是否更新成功;不存在/越权/非 websystem 返回 false。
     */
    @Transactional
    public boolean patchSourceConfig(String id, Map<String, Object> patch) {
        ExperiencePO po = mapper.selectById(id);
        if (po == null) return false;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return false;
        if (!"websystem".equals(po.getOrigin())) return false;
        Map<String, Object> cfg = parseConfig(po.getSourceConfig());
        if (patch != null) cfg.putAll(patch);
        po.setSourceConfig(toJson(cfg));
        po.setUpdatedAt(System.currentTimeMillis());
        mapper.updateById(po);
        return true;
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
        // 接入的 web 系统：把连接配置以「密码遮蔽」后的形式回传，供前端回填编辑表单（真实密码不出库）
        if ("websystem".equals(po.getOrigin()) && po.getSourceConfig() != null) {
            out.put("connection", maskConfig(parseConfig(po.getSourceConfig())));
        }
        return out;
    }

    /** 把连接配置里的密码/storageState 等敏感字段遮蔽后返回（不改动入参）。 */
    private static Map<String, Object> maskConfig(Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>(config);
        Object pwd = out.get("password");
        if (pwd != null && !String.valueOf(pwd).isEmpty()) out.put("password", MASK);
        // storageState 可能含登录态 cookie，列表/详情只回传是否已配置，不回传原文
        Object ss = out.remove("storageState");
        out.put("hasStorageState", ss != null && !String.valueOf(ss).isBlank());
        return out;
    }

    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> mp) {
                Map<String, Object> out = new LinkedHashMap<>();
                mp.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (Exception ignore) { /* 损坏的 JSON 兜底为空配置 */ }
        return new LinkedHashMap<>();
    }

    private String toJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config == null ? new LinkedHashMap<>() : config);
        } catch (Exception e) {
            return "{}";
        }
    }
}

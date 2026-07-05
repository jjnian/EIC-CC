package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
    private final ExperienceRefRepository refRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 连接配置里的敏感字段，列表/详情对外一律遮蔽，仅服务端探索时读原文。 */
    private static final String MASK = "********";

    public ExperienceRepository(ExperienceMapper mapper, ExperienceFolderMapper folderMapper,
                                ExperienceRefRepository refRepo) {
        this.mapper = mapper;
        this.folderMapper = folderMapper;
        this.refRepo = refRepo;
    }

    /** 当前工作空间「引用」的全部经验，按创建时间倒序。 */
    public List<Map<String, Object>> list() {
        return list(WorkspaceContext.required());
    }

    /**
     * 指定工作空间「引用」的全部经验（侧栏树 / 当前工作空间经验库）。
     * <p>经验库为全局公共资源，工作空间通过引用纳入：这里返回该工作空间引用的经验，并把每条的
     * {@code folderId} 覆盖为「该引用在本工作空间内的归类文件夹」（而非经验本体的 folder_id）。
     */
    public List<Map<String, Object>> list(String workspaceId) {
        Map<String, String> folderByExp = refRepo.folderByExperience(workspaceId);
        if (folderByExp.isEmpty()) return List.of();
        return mapper.selectBatchIds(folderByExp.keySet())
                .stream()
                .sorted((a, b) -> Long.compare(
                        b.getCreatedAt() == null ? 0 : b.getCreatedAt(),
                        a.getCreatedAt() == null ? 0 : a.getCreatedAt()))
                .map(po -> {
                    Map<String, Object> m = toMap(po);
                    // 侧栏按「引用所在工作空间」的归类文件夹分目录，覆盖经验本体的 folderId
                    String folderId = folderByExp.get(po.getId());
                    if (folderId != null && !folderId.isBlank()) m.put("folderId", folderId);
                    else m.remove("folderId");
                    return m;
                })
                .toList();
    }

    /** 当前工作空间「尚未引用」的公共经验（供引用选择器列出可引入的经验）。 */
    public List<Map<String, Object>> listReferencable(String workspaceId) {
        Map<String, String> referenced = refRepo.folderByExperience(workspaceId);
        return mapper.selectList(new LambdaQueryWrapper<ExperiencePO>()
                        .orderByDesc(ExperiencePO::getCreatedAt))
                .stream()
                .filter(po -> !referenced.containsKey(po.getId()))
                .map(this::toMap)
                .toList();
    }

    /** 跨工作空间的全量经验列表，按创建时间倒序。 */
    public List<Map<String, Object>> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<ExperiencePO>()
                        .orderByDesc(ExperiencePO::getCreatedAt))
                .stream().map(this::toMap).toList();
    }

    /**
     * 全量经验分页（可选按归属工作空间过滤），按创建时间倒序。经验库大到万篇时，列表按页取。
     * @param workspaceId 非空时只取该归属工作空间的经验；null/空取全部
     * @param offset 起始行（≥0）；@param limit 每页行数（调用方已 clamp）
     */
    public List<Map<String, Object>> listAllPaged(String workspaceId, int offset, int limit) {
        LambdaQueryWrapper<ExperiencePO> w = new LambdaQueryWrapper<ExperiencePO>()
                .orderByDesc(ExperiencePO::getCreatedAt);
        if (workspaceId != null && !workspaceId.isBlank()) w.eq(ExperiencePO::getWorkspaceId, workspaceId);
        w.last("LIMIT " + limit + " OFFSET " + offset);   // limit/offset 为已校验的 int，无注入风险
        return mapper.selectList(w).stream().map(this::toMap).toList();
    }

    /** 全量经验总数（分页用），过滤条件与 {@link #listAllPaged} 一致。 */
    public long countAll(String workspaceId) {
        LambdaQueryWrapper<ExperiencePO> w = new LambdaQueryWrapper<>();
        if (workspaceId != null && !workspaceId.isBlank()) w.eq(ExperiencePO::getWorkspaceId, workspaceId);
        return mapper.selectCount(w);
    }

    /** 有经验存在的归属工作空间 id 集合（供列表筛选条，避免为此加载全量经验）。 */
    public List<String> distinctWorkspaceIds() {
        return mapper.selectObjs(new QueryWrapper<ExperiencePO>().select("DISTINCT workspace_id"))
                .stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }

    /** 按 id 取一行。经验库为全局公共资源，任意工作空间均可查看，不再按归属隔离。 */
    public Map<String, Object> findFull(String id) {
        ExperiencePO po = mapper.selectById(id);
        if (po == null) return null;
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
        return upsertFromDataSource(dataSourceId, title, content, tags, "ddl");
    }

    /**
     * 数据源导出经验的 upsert（同一数据源重复导出原地刷新）。origin 按数据源类型区分：
     * 库结构导出 = "ddl"（建图走 schema 专用规则），文件转写/HTTP 接口 = "datasource"（普通散文抽取）。
     * 历史行 origin 混标为 ddl 的在下次导出时被收养并纠正。
     */
    public Map<String, Object> upsertFromDataSource(String dataSourceId, String title, String content,
                                                    String tags, String origin) {
        String ws = WorkspaceContext.required();
        String effOrigin = origin == null || origin.isBlank() ? "ddl" : origin;
        List<ExperiencePO> existing = mapper.selectList(new LambdaQueryWrapper<ExperiencePO>()
                .eq(ExperiencePO::getWorkspaceId, ws)
                .in(ExperiencePO::getOrigin, "ddl", "datasource")
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
            hit.setOrigin(effOrigin); // 纠正历史误标(如视频转写导出曾被标为 ddl)
            hit.setSourceConfig(toJson(Map.of("dataSourceId", dataSourceId)));
            hit.setUpdatedAt(System.currentTimeMillis());
            mapper.updateById(hit);
            return toMap(hit);
        }
        ExperiencePO po = newPo(title, content, tags, effOrigin);
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
        // 经验全局公共：归类作用于「当前工作空间对该经验的引用」，而非经验本体。
        // 目标文件夹须属于当前工作空间（侧栏按工作空间分目录）。
        String ws = WorkspaceContext.required();
        String target = (folderId == null || folderId.isBlank()) ? null : folderId.trim();
        if (target != null) {
            ExperienceFolderPO f = folderMapper.selectById(target);
            if (f == null || !ws.equals(f.getWorkspaceId())) {
                throw new IllegalArgumentException("目标文件夹不存在或不属于当前工作空间");
            }
        }
        return refRepo.setFolder(ws, id, target);
    }

    /** 按 id 取一行（不做工作空间隔离，索引服务在异步线程里调用，自行不依赖请求上下文）。 */
    public ExperiencePO findById(String id) {
        return mapper.selectById(id);
    }

    /** 按 id 取 PO（供预览/下载原件用）。经验全局公共，任意工作空间均可访问。 */
    public ExperiencePO findPoScoped(String id) {
        return mapper.selectById(id);
    }

    @Transactional
    public boolean delete(String id) {
        // 经验全局公共：任意工作空间均可删除；删除本体时清理其全部工作空间引用。
        ExperiencePO po = mapper.selectById(id);
        if (po == null) return false;
        refRepo.deleteByExperience(id);
        return mapper.deleteById(id) > 0;
    }

    /** 把一批经验引用进当前工作空间（已引用的跳过），返回新增条数。 */
    @Transactional
    public int reference(List<String> experienceIds) {
        return refRepo.reference(WorkspaceContext.required(), experienceIds, null);
    }

    /** 把一条经验引用进当前工作空间（用于 DDL 抽取等工作空间内动作的即时纳入）。 */
    @Transactional
    public int reference(String experienceId) {
        return refRepo.reference(WorkspaceContext.required(), List.of(experienceId), null);
    }

    /** 取消当前工作空间对某经验的引用（不删除经验本体）。 */
    @Transactional
    public boolean unreference(String experienceId) {
        return refRepo.unreference(WorkspaceContext.required(), experienceId);
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
        // 数据库 DDL 抽取的经验：回传来源数据源 id，供前端标注「来自哪个数据库」
        // （数据源名称会随重命名变化，故只存 id，由前端按数据源列表实时解析名称）。
        if ("ddl".equals(po.getOrigin()) && po.getSourceConfig() != null) {
            Object dsId = parseConfig(po.getSourceConfig()).get("dataSourceId");
            if (dsId != null && !String.valueOf(dsId).isBlank()) {
                out.put("sourceDataSourceId", String.valueOf(dsId));
            }
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

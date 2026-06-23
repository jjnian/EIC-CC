package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.mapper.DataSourceMapper;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源仓储：列表 / 新增 / 删除，按当前工作空间隔离。
 * <p>仅由 extract 流程在抽取成功后调用 {@link #saveSource}。
 */
@Repository
public class DataSourceRepository {

    /** 列表/详情里 transcript 字段的预览字符上限（全文仍存库，仅限制传输体积）。 */
    private static final int LIST_TRANSCRIPT_PREVIEW = 800;

    private final DataSourceMapper mapper;
    private final DataSourceRefRepository refRepo;
    private final JsonCodec codec;

    public DataSourceRepository(DataSourceMapper mapper, DataSourceRefRepository refRepo,
                                ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.refRepo = refRepo;
        this.codec = new JsonCodec(objectMapper);
    }

    /** 当前工作空间「引用」的全部数据源，按创建时间倒序。 */
    public List<Map<String, Object>> list() {
        return list(WorkspaceContext.required());
    }

    /**
     * 指定工作空间「引用」的全部数据源（侧栏树 / 当前工作空间数据源）。
     * <p>数据源为全局公共资源，工作空间通过引用纳入：返回该工作空间引用的数据源，并把每条的
     * {@code folderId} 覆盖为「该引用在本工作空间内的归类文件夹」（而非数据源本体的 folder_id）。
     */
    public List<Map<String, Object>> list(String workspaceId) {
        Map<String, String> folderByDs = refRepo.folderByDataSource(workspaceId);
        if (folderByDs.isEmpty()) return List.of();
        return mapper.selectBatchIds(folderByDs.keySet())
                .stream()
                .sorted((a, b) -> Long.compare(
                        b.getCreatedAt() == null ? 0 : b.getCreatedAt(),
                        a.getCreatedAt() == null ? 0 : a.getCreatedAt()))
                .map(po -> {
                    Map<String, Object> m = toMap(po);
                    String folderId = folderByDs.get(po.getId());
                    if (folderId != null && !folderId.isBlank()) m.put("folderId", folderId);
                    else m.remove("folderId");
                    return m;
                })
                .toList();
    }

    /** 当前工作空间「尚未引用」的公共数据源（供引用选择器列出可引入的数据源）。 */
    public List<Map<String, Object>> listReferencable(String workspaceId) {
        Map<String, String> referenced = refRepo.folderByDataSource(workspaceId);
        return mapper.selectList(new LambdaQueryWrapper<DataSourcePO>()
                        .orderByDesc(DataSourcePO::getCreatedAt))
                .stream()
                .filter(po -> !referenced.containsKey(po.getId()))
                .map(this::toMap)
                .toList();
    }

    /** 把一批数据源引用进当前工作空间（已引用的跳过），返回新增条数。 */
    @Transactional
    public int reference(List<String> dataSourceIds) {
        return refRepo.reference(WorkspaceContext.required(), dataSourceIds, null);
    }

    /** 取消当前工作空间对某数据源的引用（不删除数据源本体）。 */
    @Transactional
    public boolean unreference(String dataSourceId) {
        return refRepo.unreference(WorkspaceContext.required(), dataSourceId);
    }

    /** 跨工作空间的全量数据源列表，按创建时间倒序。用于「数据源」总览页(不区分工作空间)。 */
    public List<Map<String, Object>> listAll() {
        List<DataSourcePO> pos = mapper.selectList(
                new LambdaQueryWrapper<DataSourcePO>()
                        .orderByDesc(DataSourcePO::getCreatedAt));
        return pos.stream().map(this::toMap).toList();
    }

    public List<DataSourcePO> listByKind(String workspaceId, String kind) {
        return mapper.selectList(
                new LambdaQueryWrapper<DataSourcePO>()
                        .eq(DataSourcePO::getWorkspaceId, workspaceId)
                        .eq(DataSourcePO::getKind, kind)
                        .orderByDesc(DataSourcePO::getCreatedAt));
    }

    @Transactional
    public DataSourcePO saveSource(String kind, String name, String mime, Long sizeBytes,
                                   Map<String, Object> extra) {
        DataSourcePO po = new DataSourcePO();
        po.setId("ds_" + System.currentTimeMillis() + "_" + Long.toString(System.nanoTime() & 0xffff, 36));
        po.setWorkspaceId(WorkspaceContext.required());
        po.setKind(kind);
        po.setName(name);
        po.setMime(mime);
        po.setSizeBytes(sizeBytes);
        if (extra != null && !extra.isEmpty()) {
            po.setExtraJson(codec.toJson(extra));
        }
        po.setCreatedAt(System.currentTimeMillis());
        mapper.insert(po);
        return po;
    }

    /**
     * 按 (当前工作空间, kind, name) upsert 数据源：已存在则更新元信息/extra 并标记需重建索引，
     * 否则新建。用于抽取流程里同名来源（如重复上传同一音频）去重，避免堆出重复的可检索数据源。
     */
    @Transactional
    public DataSourcePO upsertSource(String kind, String name, String mime, Long sizeBytes,
                                     Map<String, Object> extra) {
        String ws = WorkspaceContext.required();
        DataSourcePO existing = mapper.selectList(new LambdaQueryWrapper<DataSourcePO>()
                        .eq(DataSourcePO::getWorkspaceId, ws)
                        .eq(DataSourcePO::getKind, kind)
                        .eq(DataSourcePO::getName, name)
                        .orderByDesc(DataSourcePO::getCreatedAt))
                .stream().findFirst().orElse(null);
        if (existing == null) {
            return saveSource(kind, name, mime, sizeBytes, extra);
        }
        existing.setMime(mime);
        existing.setSizeBytes(sizeBytes);
        existing.setExtraJson(extra != null && !extra.isEmpty() ? codec.toJson(extra) : null);
        existing.setIndexStatus("none");   // 内容已刷新，旧索引作废，交由调用方重新触发
        existing.setUpdatedAt(System.currentTimeMillis());
        mapper.updateById(existing);
        return existing;
    }

    @Transactional
    public boolean delete(String id) {
        // 数据源为全局公共资源：任意工作空间均可删除；删除本体时清理其全部工作空间引用。
        DataSourcePO existing = mapper.selectById(id);
        if (existing == null) return false;
        refRepo.deleteByDataSource(id);
        return mapper.deleteById(id) > 0;
    }

    /** 创建活数据源（mysql/pgsql/file_stored/https_api），允许显式 id。 */
    @Transactional
    public DataSourcePO create(String id, String kind, String name, String mime, Long sizeBytes,
                               Map<String, Object> config) {
        DataSourcePO po = new DataSourcePO();
        po.setId(id != null ? id : ("ds_" + System.currentTimeMillis()
                + "_" + Long.toString(System.nanoTime() & 0xffff, 36)));
        po.setWorkspaceId(WorkspaceContext.required());
        po.setKind(kind);
        po.setName(name);
        po.setMime(mime);
        po.setSizeBytes(sizeBytes);
        po.setStatus("idle");
        po.setCreatedAt(System.currentTimeMillis());
        po.setUpdatedAt(po.getCreatedAt());
        if (config != null && !config.isEmpty()) {
            po.setConfigJson(codec.toJson(config));
        }
        mapper.insert(po);
        return po;
    }

    /** 按 id 取一行（不做工作空间隔离，调用方自行校验）。 */
    public DataSourcePO findById(String id) {
        return mapper.selectById(id);
    }

    /** 解析 configJson 为 Map（兜底返回空 Map）。 */
    public Map<String, Object> readConfig(DataSourcePO po) {
        if (po == null || po.getConfigJson() == null || po.getConfigJson().isBlank()) {
            return new LinkedHashMap<>();
        }
        Object parsed = codec.readValue(po.getConfigJson(), Object.class);
        return parsed instanceof Map<?, ?> mp ? toStringMap(mp) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringMap(Map<?, ?> mp) {
        Map<String, Object> out = new LinkedHashMap<>();
        mp.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    @Transactional
    public boolean updateConfig(String id, String newName, Map<String, Object> newConfig) {
        DataSourcePO po = mapper.selectById(id);
        if (po == null) return false;
        if (newName != null && !newName.isBlank()) po.setName(newName);
        if (newConfig != null) po.setConfigJson(codec.toJson(newConfig));
        po.setUpdatedAt(System.currentTimeMillis());
        return mapper.updateById(po) > 0;
    }

    /**
     * 把数据源移动到指定文件夹（folderId 为 null/空 = 移到根）。
     * <p>数据源全局公共：归类作用于「当前工作空间对该数据源的引用」，而非数据源本体，
     * 各工作空间各自归类、互不影响。
     */
    @Transactional
    public boolean moveToFolder(String id, String folderId) {
        return refRepo.setFolder(WorkspaceContext.required(), id, folderId);
    }

    @Transactional
    public void markStatus(String id, String status, String errorMsg) {
        DataSourcePO po = mapper.selectById(id);
        if (po == null) return;
        po.setStatus(status);
        po.setLastTestedAt(System.currentTimeMillis());
        po.setLastError(errorMsg);
        mapper.updateById(po);
    }

    /** 跨工作空间的全量加载，用于启动期调度器扫描所有 https_api 任务。 */
    public List<DataSourcePO> listAllByKind(String kind) {
        return mapper.selectList(new LambdaQueryWrapper<DataSourcePO>()
                .eq(DataSourcePO::getKind, kind));
    }

    private Map<String, Object> toMap(DataSourcePO po) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", po.getId());
        out.put("workspaceId", po.getWorkspaceId());
        out.put("kind", po.getKind());
        out.put("name", po.getName());
        if (po.getMime() != null) out.put("mime", po.getMime());
        if (po.getSizeBytes() != null) out.put("size", po.getSizeBytes());
        out.put("createdAt", po.getCreatedAt());
        if (po.getFolderId() != null)      out.put("folderId", po.getFolderId());
        if (po.getStatus() != null)        out.put("status", po.getStatus());
        if (po.getLastTestedAt() != null)  out.put("lastTestedAt", po.getLastTestedAt());
        if (po.getLastError() != null)     out.put("lastError", po.getLastError());
        if (po.getUpdatedAt() != null)     out.put("updatedAt", po.getUpdatedAt());
        if (po.getConfigJson() != null && !po.getConfigJson().isBlank()) {
            Object cfg = codec.readValue(po.getConfigJson(), Object.class);
            if (cfg instanceof Map<?, ?> mp) {
                Map<String, Object> safe = maskSensitive(toStringMap(mp));
                out.put("config", safe);
            }
        }
        if (po.getExtraJson() != null && !po.getExtraJson().isBlank()) {
            Object extra = codec.readValue(po.getExtraJson(), Object.class);
            if (extra instanceof Map<?, ?> mp) {
                for (Map.Entry<?, ?> e : mp.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (out.containsKey(k)) continue;
                    Object v = e.getValue();
                    // transcript 可能很大(上限 100k)，列表/详情只回传预览，避免每次拉取数据源都拖大 payload；
                    // 全文仍存于 extra_json，供 RAG 索引与「抽取到经验库」直接读库使用。
                    if ("transcript".equals(k) && v instanceof String s && s.length() > LIST_TRANSCRIPT_PREVIEW) {
                        out.put(k, s.substring(0, LIST_TRANSCRIPT_PREVIEW) + "…");
                    } else {
                        out.put(k, v);
                    }
                }
            }
        }
        return out;
    }

    /** 列表返回时把 password/Authorization 等敏感字段遮蔽。编辑表单单独调 /detail 端点拿原文。 */
    private static Map<String, Object> maskSensitive(Map<String, Object> cfg) {
        return maskConfig(cfg);
    }

    /** 敏感字段遮蔽串：detail/list 回前端用它占位，更新时见到它表示"沿用原值"。 */
    public static final String MASK = "********";

    /** 把 config 中的 password 与 headers 里的鉴权字段遮蔽为 {@link #MASK}（不改原 Map）。 */
    public static Map<String, Object> maskConfig(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>(cfg);
        if (out.get("password") instanceof String s && !s.isBlank()) out.put("password", MASK);
        Object hdrs = out.get("headers");
        if (hdrs instanceof Map<?, ?> mp) {
            Map<String, Object> mh = new LinkedHashMap<>();
            mp.forEach((k, v) -> {
                String kk = String.valueOf(k);
                if (isSensitiveHeader(kk) && v instanceof String sv && !sv.isBlank()) {
                    mh.put(kk, MASK);
                } else {
                    mh.put(kk, v);
                }
            });
            out.put("headers", mh);
        }
        return out;
    }

    /**
     * 更新时把"被遮蔽的占位值"还原成已存配置里的原值：编辑表单提交 {@link #MASK}（或留空）表示
     * 用户没改该敏感字段，应沿用原密码/token，而不是把 {@code ********} 真写进库。
     */
    public static Map<String, Object> mergeMaskedConfig(Map<String, Object> incoming, Map<String, Object> existing) {
        if (incoming == null) return existing;
        Map<String, Object> out = new LinkedHashMap<>(incoming);
        Object pwd = out.get("password");
        if (pwd == null || (pwd instanceof String s && (s.isBlank() || MASK.equals(s)))) {
            if (existing.get("password") != null) out.put("password", existing.get("password"));
        }
        if (out.get("headers") instanceof Map<?, ?> inHdrs && existing.get("headers") instanceof Map<?, ?> exHdrs) {
            Map<String, Object> merged = new LinkedHashMap<>();
            inHdrs.forEach((k, v) -> {
                String kk = String.valueOf(k);
                if (isSensitiveHeader(kk) && (v == null || MASK.equals(String.valueOf(v)))) {
                    Object ex = ((Map<?, ?>) exHdrs).get(k);
                    merged.put(kk, ex != null ? ex : v);
                } else {
                    merged.put(kk, v);
                }
            });
            out.put("headers", merged);
        }
        return out;
    }

    private static boolean isSensitiveHeader(String key) {
        String k = key.toLowerCase();
        return k.equals("authorization") || k.contains("token") || k.contains("apikey") || k.contains("api-key");
    }
}

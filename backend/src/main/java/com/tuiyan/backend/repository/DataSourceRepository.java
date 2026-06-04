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

    private final DataSourceMapper mapper;
    private final JsonCodec codec;

    public DataSourceRepository(DataSourceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.codec = new JsonCodec(objectMapper);
    }

    /** 当前工作空间下全部数据源，按创建时间倒序。 */
    public List<Map<String, Object>> list() {
        return list(WorkspaceContext.required());
    }

    /** 指定工作空间下全部数据源（侧栏跨工作空间懒加载）。 */
    public List<Map<String, Object>> list(String workspaceId) {
        List<DataSourcePO> pos = mapper.selectList(
                new LambdaQueryWrapper<DataSourcePO>()
                        .eq(DataSourcePO::getWorkspaceId, workspaceId)
                        .orderByDesc(DataSourcePO::getCreatedAt));
        return pos.stream().map(this::toMap).toList();
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

    @Transactional
    public boolean delete(String id) {
        DataSourcePO existing = mapper.selectById(id);
        if (existing == null) return false;
        if (!WorkspaceContext.required().equals(existing.getWorkspaceId())) return false;
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
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return false;
        if (newName != null && !newName.isBlank()) po.setName(newName);
        if (newConfig != null) po.setConfigJson(codec.toJson(newConfig));
        po.setUpdatedAt(System.currentTimeMillis());
        return mapper.updateById(po) > 0;
    }

    /** 把数据源移动到指定文件夹（folderId 为 null/空 = 移到工作空间根）。按工作空间隔离。 */
    @Transactional
    public boolean moveToFolder(String id, String folderId) {
        DataSourcePO po = mapper.selectById(id);
        if (po == null) return false;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return false;
        po.setFolderId(folderId == null || folderId.isBlank() ? null : folderId.trim());
        po.setUpdatedAt(System.currentTimeMillis());
        return mapper.updateById(po) > 0;
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
                    if (!out.containsKey(k)) out.put(k, e.getValue());
                }
            }
        }
        return out;
    }

    /** 列表返回时把 password/Authorization 等敏感字段遮蔽。编辑表单单独调 /detail 端点拿原文。 */
    private static Map<String, Object> maskSensitive(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>(cfg);
        if (out.containsKey("password")) out.put("password", "********");
        Object hdrs = out.get("headers");
        if (hdrs instanceof Map<?, ?> mp) {
            Map<String, Object> mh = new LinkedHashMap<>();
            mp.forEach((k, v) -> {
                String kk = String.valueOf(k);
                if (kk.equalsIgnoreCase("authorization") || kk.toLowerCase().contains("token")
                        || kk.toLowerCase().contains("apikey") || kk.toLowerCase().contains("api-key")) {
                    mh.put(kk, "********");
                } else {
                    mh.put(kk, v);
                }
            });
            out.put("headers", mh);
        }
        return out;
    }
}

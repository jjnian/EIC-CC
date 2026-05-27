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

    private Map<String, Object> toMap(DataSourcePO po) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", po.getId());
        out.put("kind", po.getKind());
        out.put("name", po.getName());
        if (po.getMime() != null) out.put("mime", po.getMime());
        if (po.getSizeBytes() != null) out.put("size", po.getSizeBytes());
        out.put("createdAt", po.getCreatedAt());
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
}

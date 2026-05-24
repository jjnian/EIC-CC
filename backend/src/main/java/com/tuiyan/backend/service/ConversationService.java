package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.AppPaths;
import com.tuiyan.backend.model.Conversation;
import com.tuiyan.backend.util.JsonAtomic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 对话历史的增删改查。
 * <p>每个 {@link Conversation} 单独落盘为 {@code ~/.tuiyan/conversations/<id>.json}，
 * 不做内存缓存：本项目数据量小，每次请求都读盘性能足够，避免缓存一致性问题。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppPaths appPaths;

    public ConversationService(AppPaths appPaths) {
        this.appPaths = appPaths;
        File d = appPaths.conversationsDir();
        if (!d.exists()) d.mkdirs();
    }

    /**
     * 列出全部对话，按 updatedAt 倒序（最近活跃的在前）。
     * <p>损坏文件不抛错，仅记日志后跳过，避免一个坏文件让整个列表加载失败。
     */
    public List<Conversation> list() {
        File d = appPaths.conversationsDir();
        File[] files = d.listFiles((f, n) -> n.endsWith(".json"));
        List<Conversation> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) {
            try {
                out.add(objectMapper.readValue(f, Conversation.class));
            } catch (IOException ioe) {
                log.warn("skip malformed conversation file {}: {}", f, ioe.toString());
            }
        }
        out.sort(Comparator.comparingLong(Conversation::getUpdatedAt).reversed());
        return out;
    }

    /** 按 id 读取；不存在返回 null（由 controller 决定是 404 还是回退）。 */
    public Conversation get(String id) throws IOException {
        File f = fileFor(id);
        if (!f.exists()) return null;
        return objectMapper.readValue(f, Conversation.class);
    }

    /**
     * 保存（新建或更新）。
     * <p>id 为空时自动生成 {@code conv_<时间戳>}；createdAt 缺省时设为当前；updatedAt 每次都刷新。
     */
    public Conversation save(Conversation c) throws IOException {
        if (c.getId() == null || c.getId().isBlank()) {
            c.setId("conv_" + System.currentTimeMillis());
        }
        long now = System.currentTimeMillis();
        if (c.getCreatedAt() == 0L) c.setCreatedAt(now);
        c.setUpdatedAt(now);
        JsonAtomic.write(objectMapper, fileFor(c.getId()), c);
        return c;
    }

    /** 按指定 id 更新；id 强制覆写，避免 controller 在路径上拿到的 id 与 body 中的不一致。 */
    public Conversation update(String id, Conversation c) throws IOException {
        c.setId(id);
        return save(c);
    }

    /** 删除指定对话；不存在返回 false 不报错。 */
    public boolean delete(String id) {
        File f = fileFor(id);
        return f.exists() && f.delete();
    }

    /** id 清洗后定位实际文件，防止路径穿越。 */
    private File fileFor(String id) {
        String safe = id.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return new File(appPaths.conversationsDir(), safe + ".json");
    }
}

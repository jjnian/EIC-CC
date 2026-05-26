package com.tuiyan.backend.service;

import com.tuiyan.backend.model.Conversation;
import com.tuiyan.backend.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话历史的增删改查。
 * <p>持久化由 {@link ConversationRepository} 完成，service 只做字段补齐和签名转发。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversationRepository;

    public ConversationService(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    /** 列出全部对话，按 updatedAt 倒序（最近活跃的在前）。 */
    public List<Conversation> list() {
        return conversationRepository.list();
    }

    /** 按 id 读取；不存在返回 null。 */
    public Conversation get(String id) {
        return conversationRepository.get(id);
    }

    /**
     * 保存（新建或更新）。
     * <p>id 为空时自动生成；createdAt 缺省时设为当前；updatedAt 每次都刷新。
     */
    public Conversation save(Conversation c) {
        if (c.getId() == null || c.getId().isBlank()) {
            c.setId("conv_" + System.currentTimeMillis());
        }
        long now = System.currentTimeMillis();
        if (c.getCreatedAt() == 0L) c.setCreatedAt(now);
        c.setUpdatedAt(now);
        conversationRepository.save(c);
        return c;
    }

    /** 按指定 id 更新；id 强制覆写，避免 controller 在路径上拿到的 id 与 body 中的不一致。 */
    public Conversation update(String id, Conversation c) {
        c.setId(id);
        return save(c);
    }

    public boolean delete(String id) {
        return conversationRepository.delete(id);
    }
}

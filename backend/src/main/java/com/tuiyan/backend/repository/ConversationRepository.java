package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.ConversationMessagePO;
import com.tuiyan.backend.entity.ConversationPO;
import com.tuiyan.backend.mapper.ConversationMapper;
import com.tuiyan.backend.mapper.ConversationMessageMapper;
import com.tuiyan.backend.model.Conversation;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话历史仓储：处理 Conversation 与底层 conversation + conversation_message 表的转换。
 * <p>消息中除 role / content 外的扩展字段（附件、@引用、推演消息等）整体序列化为 payload_json。
 */
@Repository
public class ConversationRepository {

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final JsonCodec codec;

    public ConversationRepository(ConversationMapper conversationMapper,
                                  ConversationMessageMapper messageMapper,
                                  ObjectMapper objectMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.codec = new JsonCodec(objectMapper);
    }

    /** 列出当前工作空间的所有对话（含完整消息列表），按 updated_at 倒序。 */
    public List<Conversation> list() {
        return list(WorkspaceContext.required());
    }

    /** 列出指定工作空间的所有对话（用于侧栏跨工作空间懒加载）。 */
    public List<Conversation> list(String workspaceId) {
        List<ConversationPO> pos = conversationMapper.selectList(
                new LambdaQueryWrapper<ConversationPO>()
                        .eq(ConversationPO::getWorkspaceId, workspaceId)
                        .orderByDesc(ConversationPO::getUpdatedAt));
        List<Conversation> out = new ArrayList<>(pos.size());
        for (ConversationPO po : pos) {
            out.add(loadConversation(po));
        }
        return out;
    }

    public Conversation get(String id) {
        ConversationPO po = conversationMapper.selectById(id);
        if (po == null) return null;
        if (!WorkspaceContext.required().equals(po.getWorkspaceId())) return null;
        return loadConversation(po);
    }

    @Transactional
    public void save(Conversation c) {
        ConversationPO po = new ConversationPO();
        po.setId(c.getId());
        po.setTitle(c.getTitle());
        po.setCreatedAt(c.getCreatedAt());
        po.setUpdatedAt(c.getUpdatedAt());
        ConversationPO existing = conversationMapper.selectById(c.getId());
        if (existing == null) {
            po.setWorkspaceId(WorkspaceContext.required());
            conversationMapper.insert(po);
        } else {
            if (!WorkspaceContext.required().equals(existing.getWorkspaceId())) {
                throw new IllegalArgumentException("Conversation does not belong to current workspace: " + c.getId());
            }
            po.setWorkspaceId(existing.getWorkspaceId());
            conversationMapper.updateById(po);
        }
        // 覆盖式重写消息列表
        messageMapper.delete(new LambdaQueryWrapper<ConversationMessagePO>()
                .eq(ConversationMessagePO::getConversationId, c.getId()));
        if (c.getMsgs() != null) {
            int seq = 0;
            for (Map<String, Object> msg : c.getMsgs()) {
                insertMessage(c.getId(), seq++, msg);
            }
        }
    }

    @Transactional
    public boolean delete(String id) {
        ConversationPO existing = conversationMapper.selectById(id);
        if (existing == null) return false;
        if (!WorkspaceContext.required().equals(existing.getWorkspaceId())) return false;
        return conversationMapper.deleteById(id) > 0;
    }

    // ---------- 内部 ----------

    private Conversation loadConversation(ConversationPO po) {
        Conversation c = new Conversation();
        c.setId(po.getId());
        c.setTitle(po.getTitle());
        c.setCreatedAt(po.getCreatedAt() == null ? 0L : po.getCreatedAt());
        c.setUpdatedAt(po.getUpdatedAt() == null ? 0L : po.getUpdatedAt());

        List<ConversationMessagePO> msgs = messageMapper.selectList(
                new LambdaQueryWrapper<ConversationMessagePO>()
                        .eq(ConversationMessagePO::getConversationId, po.getId())
                        .orderByAsc(ConversationMessagePO::getSeqNo));
        List<Map<String, Object>> out = new ArrayList<>(msgs.size());
        for (ConversationMessagePO m : msgs) {
            Map<String, Object> one = new LinkedHashMap<>();
            if (m.getRole() != null) one.put("role", m.getRole());
            if (m.getContent() != null) one.put("content", m.getContent());
            if (m.getCreatedAt() != null) one.put("timestamp", m.getCreatedAt());
            // payload_json 还原回来后摊平合入 map（保留原始扩展字段）
            if (m.getPayloadJson() != null && !m.getPayloadJson().isBlank()) {
                Object payload = codec.readValue(m.getPayloadJson(), Object.class);
                if (payload instanceof Map<?, ?> mp) {
                    for (Map.Entry<?, ?> e : mp.entrySet()) {
                        String k = String.valueOf(e.getKey());
                        // 不覆盖已设的标准字段
                        if (!one.containsKey(k)) one.put(k, e.getValue());
                    }
                }
            }
            out.add(one);
        }
        c.setMsgs(out);
        return c;
    }

    private void insertMessage(String conversationId, int seqNo, Map<String, Object> msg) {
        ConversationMessagePO po = new ConversationMessagePO();
        po.setConversationId(conversationId);
        po.setSeqNo(seqNo);
        po.setRole(asString(msg.get("role")));
        po.setContent(asString(msg.get("content")));
        Object ts = msg.get("timestamp");
        if (ts instanceof Number n) po.setCreatedAt(n.longValue());

        // 把除 role / content / timestamp 外的所有扩展字段汇总到 payload_json
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : msg.entrySet()) {
            String k = e.getKey();
            if ("role".equals(k) || "content".equals(k) || "timestamp".equals(k)) continue;
            payload.put(k, e.getValue());
        }
        po.setPayloadJson(payload.isEmpty() ? null : codec.toJson(payload));
        messageMapper.insert(po);
    }

    private static String asString(Object v) { return v == null ? null : String.valueOf(v); }
}

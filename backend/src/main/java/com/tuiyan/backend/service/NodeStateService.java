package com.tuiyan.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.entity.NodeDataBindingPO;
import com.tuiyan.backend.entity.NodeStateHistoryPO;
import com.tuiyan.backend.entity.NodeStatePO;
import com.tuiyan.backend.mapper.NodeStateHistoryMapper;
import com.tuiyan.backend.mapper.NodeStateMapper;
import com.tuiyan.backend.model.dto.SqlExecuteResponse;
import com.tuiyan.backend.repository.DataSourceRepository;
import com.tuiyan.backend.repository.NodeDataBindingRepository;
import com.tuiyan.backend.service.connector.JdbcConnectorService;
import com.tuiyan.backend.support.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 态势层·节点状态服务：执行绑定上配置的「状态查询」（只读 SQL，首行首列为状态值），
 * 按阈值规则判级（normal / warn / alert），落 {@code node_state}（当前）与
 * {@code node_state_history}（时序，按绑定保留最近 {@value #HISTORY_KEEP} 条）。
 * <p>「建图是画世界，供血让世界活起来」——绑定从佐证工具升级为持续给节点回填运行状态的状态源。
 * <p>SQL 安全与取数供血同一套约束：走 {@link JdbcConnectorService#executeSql} 的只读白名单 +
 * 危险函数黑名单 + 强制 LIMIT。刷新入口分两类：用户端点（工作空间隔离）与调度器
 * （无请求上下文，直查仓储，与 HttpScheduler 同一模式）。
 */
@Service
public class NodeStateService {

    private static final Logger log = LoggerFactory.getLogger(NodeStateService.class);

    /** 每绑定保留的历史条数。 */
    public static final int HISTORY_KEEP = 500;

    private final NodeDataBindingRepository bindingRepo;
    private final DataSourceRepository dsRepo;
    private final JdbcConnectorService jdbc;
    private final NodeStateMapper stateMapper;
    private final NodeStateHistoryMapper historyMapper;
    private final ObjectMapper om = new ObjectMapper();

    public NodeStateService(NodeDataBindingRepository bindingRepo,
                            DataSourceRepository dsRepo,
                            JdbcConnectorService jdbc,
                            NodeStateMapper stateMapper,
                            NodeStateHistoryMapper historyMapper) {
        this.bindingRepo = bindingRepo;
        this.dsRepo = dsRepo;
        this.jdbc = jdbc;
        this.stateMapper = stateMapper;
        this.historyMapper = historyMapper;
    }

    /**
     * 刷新一条绑定的状态（调度器与手动刷新共用；不做工作空间校验，调用方自行保证）。
     * 采集失败不抛错：状态落为 {@code error} 级并带错误信息，让态势图能显示"探针失联"。
     * @return 刷新后的状态 map；绑定不存在或未配置状态查询时抛 IllegalArgumentException
     */
    public Map<String, Object> refresh(String bindingId) {
        NodeDataBindingPO b = bindingRepo.findAnyById(bindingId);
        if (b == null) throw new IllegalArgumentException("绑定不存在: " + bindingId);
        if (b.getStatusQuery() == null || b.getStatusQuery().isBlank()) {
            throw new IllegalArgumentException("该绑定未配置状态查询");
        }
        String value = null, level, message = null;
        try {
            DataSourcePO ds = dsRepo.findById(b.getDataSourceId());
            if (ds == null) throw new IllegalStateException("绑定的数据源已不存在");
            SqlExecuteResponse r = jdbc.executeSql(ds.getKind(), dsRepo.readConfig(ds), b.getStatusQuery(), 1);
            Object cell = r.getRows() != null && !r.getRows().isEmpty() && !r.getRows().get(0).isEmpty()
                    ? r.getRows().get(0).get(0) : null;
            value = cell == null ? null : String.valueOf(cell);
            level = evaluate(b.getStatusRulesJson(), value);
        } catch (Exception e) {
            level = "error";
            message = e.getMessage() == null ? e.toString() : e.getMessage();
            if (message.length() > 500) message = message.substring(0, 500);
            log.warn("[node-state] 状态采集失败 binding={} err={}", bindingId, message);
        }

        long now = System.currentTimeMillis();
        NodeStatePO st = new NodeStatePO();
        st.setBindingId(b.getId());
        st.setWorkspaceId(b.getWorkspaceId());
        st.setModelId(b.getModelId());
        st.setNodeId(b.getNodeId());
        st.setValue(value);
        st.setLevel(level);
        st.setMessage(message);
        st.setUpdatedAt(now);
        if (stateMapper.updateById(st) == 0) stateMapper.insert(st);

        NodeStateHistoryPO h = new NodeStateHistoryPO();
        h.setBindingId(b.getId());
        h.setModelId(b.getModelId());
        h.setNodeId(b.getNodeId());
        h.setValue(value);
        h.setLevel(level);
        h.setCollectedAt(now);
        historyMapper.insert(h);
        pruneHistory(b.getId());

        return stateToMap(st);
    }

    /** 某模型下全部节点的当前状态（工作空间隔离，供态势画布轮询）。 */
    public List<Map<String, Object>> statesOfModel(String modelId) {
        List<NodeStatePO> list = stateMapper.selectList(new LambdaQueryWrapper<NodeStatePO>()
                .eq(NodeStatePO::getWorkspaceId, WorkspaceContext.required())
                .eq(NodeStatePO::getModelId, modelId));
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (NodeStatePO st : list) out.add(stateToMap(st));
        return out;
    }

    /** 某绑定的状态历史（新→旧，最多 limit 条；含归属校验）。 */
    public List<Map<String, Object>> history(String bindingId, int limit) {
        if (bindingRepo.findScoped(bindingId) == null) {
            throw new IllegalArgumentException("绑定不存在: " + bindingId);
        }
        int n = Math.max(1, Math.min(limit, HISTORY_KEEP));
        List<NodeStateHistoryPO> list = historyMapper.selectList(new LambdaQueryWrapper<NodeStateHistoryPO>()
                .eq(NodeStateHistoryPO::getBindingId, bindingId)
                .orderByDesc(NodeStateHistoryPO::getCollectedAt)
                .last("LIMIT " + n));
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (NodeStateHistoryPO h : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", h.getValue());
            m.put("level", h.getLevel());
            m.put("collectedAt", h.getCollectedAt());
            out.add(m);
        }
        return out;
    }

    /** 删除绑定时连带清理其状态与历史（避免悬挂状态继续给节点着色）。 */
    public void deleteByBinding(String bindingId) {
        stateMapper.deleteById(bindingId);
        historyMapper.delete(new LambdaQueryWrapper<NodeStateHistoryPO>()
                .eq(NodeStateHistoryPO::getBindingId, bindingId));
    }

    /**
     * 阈值判级：规则 JSON {@code [{level,op,value}]} 按序首个命中生效，都不命中/无规则为 normal。
     * op：gt/gte/lt/lte（数值比较，任一方非数值不命中）、eq/ne（数值优先，退化字符串）、contains（子串）。
     */
    String evaluate(String rulesJson, String value) {
        if (rulesJson == null || rulesJson.isBlank()) return "normal";
        JsonNode rules;
        try {
            rules = om.readTree(rulesJson);
        } catch (Exception e) {
            return "normal";   // 规则损坏不阻塞采集
        }
        if (!rules.isArray()) return "normal";
        for (JsonNode r : rules) {
            String level = r.path("level").asText("");
            String op = r.path("op").asText("");
            String target = r.path("value").asText("");
            if (level.isBlank() || op.isBlank()) continue;
            if (matches(op, value, target)) return level;
        }
        return "normal";
    }

    private static boolean matches(String op, String value, String target) {
        if (value == null) return false;
        Double a = parseNum(value), b = parseNum(target);
        switch (op) {
            case "gt":  return a != null && b != null && a > b;
            case "gte": return a != null && b != null && a >= b;
            case "lt":  return a != null && b != null && a < b;
            case "lte": return a != null && b != null && a <= b;
            case "eq":  return a != null && b != null ? a.doubleValue() == b.doubleValue() : value.equals(target);
            case "ne":  return a != null && b != null ? a.doubleValue() != b.doubleValue() : !value.equals(target);
            case "contains": return value.contains(target);
            default: return false;
        }
    }

    private static Double parseNum(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 按绑定裁剪历史到最近 {@value #HISTORY_KEEP} 条。 */
    private void pruneHistory(String bindingId) {
        Long count = historyMapper.selectCount(new LambdaQueryWrapper<NodeStateHistoryPO>()
                .eq(NodeStateHistoryPO::getBindingId, bindingId));
        if (count == null || count <= HISTORY_KEEP) return;
        // 找出第 HISTORY_KEEP 新的 collected_at，删除更旧的行（同刻多行时略多删，可接受）
        List<NodeStateHistoryPO> edge = historyMapper.selectList(new LambdaQueryWrapper<NodeStateHistoryPO>()
                .eq(NodeStateHistoryPO::getBindingId, bindingId)
                .orderByDesc(NodeStateHistoryPO::getCollectedAt)
                .last("LIMIT 1 OFFSET " + (HISTORY_KEEP - 1)));
        if (edge.isEmpty()) return;
        historyMapper.delete(new LambdaQueryWrapper<NodeStateHistoryPO>()
                .eq(NodeStateHistoryPO::getBindingId, bindingId)
                .lt(NodeStateHistoryPO::getCollectedAt, edge.get(0).getCollectedAt()));
    }

    private static Map<String, Object> stateToMap(NodeStatePO st) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bindingId", st.getBindingId());
        m.put("nodeId", st.getNodeId());
        m.put("value", st.getValue());
        m.put("level", st.getLevel());
        if (st.getMessage() != null) m.put("message", st.getMessage());
        m.put("updatedAt", st.getUpdatedAt());
        return m;
    }
}

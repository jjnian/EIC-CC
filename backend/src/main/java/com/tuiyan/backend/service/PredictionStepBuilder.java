package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuiyan.backend.support.IdSaltRewriter;
import com.tuiyan.backend.support.PredictionMath;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推演单步计算器：承载原 {@code PredictionOrchestrator.buildStep} 的纯计算逻辑。
 * <p>职责：把 LLM 返回的单步 chain item 转换为画布节点、边、chain 记录，并计算
 * effectiveProbability（noisy-OR 级联）与 cumulativeCredibility（最可靠上游路径）。
 * <p>该类不依赖 SSE、不做剪枝编排：跨步共享的可变状态（effProb / cumCredibility /
 * perStepCount / blockedIds）与每次推演不变的配置都通过 {@link Context} 显式传入，
 * 由调用方（{@code run()}）持有，保证跨步状态累积语义不变。
 */
@Component
public class PredictionStepBuilder {

    // 节点在画布上的水平 / 垂直步距（像素），用于按推演步数自动排布
    private static final double X_STEP = 220;
    private static final double Y_STEP = 100;

    /** 单步构造的结果容器；当所有上游均被 block 时，{@link #buildStep} 返回 null 触发剪枝。 */
    public static class StepBuildResult {
        Map<String, Object> node;
        List<Map<String, Object>> edges;
        Map<String, Object> chainItem;
        int step;
    }

    /**
     * 跨步共享的可变上下文：由 {@code run()} 在整条 chain 遍历期间持有并复用。
     * <p>承载两类数据：
     * <ul>
     *   <li><b>每次推演不变的配置</b>：idSalt / currentChainIds / backward / 坐标原点 / direction；</li>
     *   <li><b>跨步就地累积的可变状态</b>：blockedIds / effProb / cumCredibility / perStepCount，
     *       每一步对它们的更新对后续步骤可见，这正是原 buildStep 通过共享 Map 实现的语义。</li>
     * </ul>
     * 可变集合通过 getter 返回活引用，调用方既可在循环前用先验初始化，也可在剪枝时追加。
     */
    public static class Context {
        // ===== 每次推演不变的配置 =====
        private final String idSalt;
        private final Set<String> currentChainIds;
        private final boolean backward;
        private final double baseX;
        private final double baseY;
        private final int direction;

        // ===== 跨步就地累积的可变状态 =====
        // what-if block 模式要剪枝的节点 id（含连带剪枝时追加的自身 id）
        private final Set<String> blockedIds = new HashSet<>();
        // 每个节点的"有效概率"（联合上游概率后的结果），用于级联计算
        private final Map<String, Double> effProb = new HashMap<>();
        // 从 seed 到当前节点的累积可信度，区别于单步置信度
        private final Map<String, Double> cumCredibility = new HashMap<>();
        // 每一步的节点数计数器，用于同步槽位（slot）排布，避免节点重叠
        private final Map<Integer, Integer> perStepCount = new HashMap<>();

        public Context(String idSalt, Set<String> currentChainIds, boolean backward,
                       double baseX, double baseY, int direction) {
            this.idSalt = idSalt;
            this.currentChainIds = currentChainIds;
            this.backward = backward;
            this.baseX = baseX;
            this.baseY = baseY;
            this.direction = direction;
        }

        public String idSalt() { return idSalt; }
        public Set<String> currentChainIds() { return currentChainIds; }
        public boolean backward() { return backward; }
        public double baseX() { return baseX; }
        public double baseY() { return baseY; }
        public int direction() { return direction; }

        public Set<String> blockedIds() { return blockedIds; }
        public Map<String, Double> effProb() { return effProb; }
        public Map<String, Double> cumCredibility() { return cumCredibility; }
        public Map<Integer, Integer> perStepCount() { return perStepCount; }
    }

    /**
     * 把 LLM 返回的单步 chain item 转换为画布节点、边、chain 记录。
     * <p>核心计算：
     * <ul>
     *   <li>effectiveProbability：正向时 = confidence × (1 - ∏(1 - 上游 effProb))，溯因或无上游时直接取 confidence；</li>
     *   <li>cumulativeCredibility：effProb × max(上游 cumCred)，反映从 seed 一路走到此节点的整体可信度。</li>
     * </ul>
     * 若 rawLinkIds 非空但被全部剪光，返回 null 触发剪枝。
     *
     * @param item      LLM 返回的单步 chain item
     * @param stepIndex 1-based 步序（用于兜底 id / label / step）
     * @param ctx       跨步共享上下文，本方法会就地更新其 effProb / cumCredibility / perStepCount
     */
    public StepBuildResult buildStep(JsonNode item, int stepIndex, Context ctx) {
        String rawId = item.path("id").asText("p_" + stepIndex);
        // 给本批新增节点 id 加盐，保证 fork 时与父分支不冲突；引用祖先节点则保持原 id
        String id = IdSaltRewriter.applyPredictionIdSalt(rawId, ctx.idSalt(), ctx.currentChainIds());
        String label = item.path("label").asText("预测" + stepIndex);
        String type = item.path("type").asText("event");
        String ruleId = item.path("rule_id").isNull() ? null : item.path("rule_id").asText(null);
        String explanation = item.path("explanation").asText("");
        double confidence = item.path("confidence").asDouble(0.6);
        int step = item.path("step").asInt(stepIndex);

        // 溯因取 leads_to（"这个节点导致什么"）；正向取 triggered_by（"被什么触发"）
        // 兼容 LLM 偶尔字段反着写的情况
        JsonNode linkNode = ctx.backward()
                ? (item.has("leads_to") ? item.get("leads_to") : item.path("triggered_by"))
                : (item.has("triggered_by") ? item.get("triggered_by") : item.path("leads_to"));

        List<String> rawLinkIds = new ArrayList<>();
        if (linkNode != null && linkNode.isArray()) {
            for (JsonNode t : linkNode) {
                rawLinkIds.add(IdSaltRewriter.applyPredictionIdSalt(t.asText(), ctx.idSalt(), ctx.currentChainIds()));
            }
        }
        // 过滤掉已被 block 的上游
        List<String> linkIds = new ArrayList<>();
        for (String lid : rawLinkIds) {
            if (!ctx.blockedIds().contains(lid)) linkIds.add(lid);
        }
        // LLM 原本声明了上游，但全被剪光 → 本节点失去依据，整体剪枝
        if (!rawLinkIds.isEmpty() && linkIds.isEmpty()) {
            return null;
        }

        // 同一 step 中节点垂直堆叠：slot 用于上下错位避免重叠
        int slot = ctx.perStepCount().getOrDefault(step, 0);
        ctx.perStepCount().put(step, slot + 1);
        double nx = ctx.baseX() + ctx.direction() * step * X_STEP;
        double ny = ctx.baseY() + (slot - 0.5) * Y_STEP;

        // 计算 effectiveProbability（有效概率）
        double pEff;
        if (ctx.backward() || linkIds.isEmpty()) {
            // 溯因或无上游：取节点自身置信度，不级联
            pEff = PredictionMath.clamp01(confidence);
        } else {
            // 正向：用 noisy-OR 公式合并多个上游概率（任一上游触发即可触发本节点）
            // notOr = ∏(1 - p_i)，即"所有上游都不触发"的概率
            double notOr = 1.0;
            for (String pid : linkIds) {
                double pp = ctx.effProb().containsKey(pid) ? ctx.effProb().get(pid) : 1.0;
                notOr *= (1.0 - PredictionMath.clamp01(pp));
            }
            // 最终概率 = 自身置信度 × (至少一个上游触发的概率)
            pEff = PredictionMath.clamp01(confidence) * (1.0 - notOr);
        }
        ctx.effProb().put(id, pEff);

        // 计算累积置信度：与 effProb 不同，cumCred 反映"链路最薄弱的环节"，
        // 取上游 cumCred 的最大值（最可靠路径），再乘以本节点 effProb
        double cumCred;
        if (linkIds.isEmpty()) {
            cumCred = PredictionMath.round3(pEff);
        } else {
            double maxUpstream = 0.0;
            for (String pid : linkIds) {
                double up = ctx.cumCredibility().containsKey(pid) ? ctx.cumCredibility().get(pid) : 1.0;
                if (up > maxUpstream) maxUpstream = up;
            }
            cumCred = PredictionMath.round3(pEff * maxUpstream);
        }
        ctx.cumCredibility().put(id, cumCred);

        // 组装节点对象（前端 OntologyNode 形状），source=predicted 用来与 trunk 节点区分
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("label", label);
        node.put("type", type);
        node.put("source", "predicted");
        node.put("predictedStep", step);
        node.put("predictedIntent", ctx.backward() ? "backward" : "forward");
        node.put("confidence", confidence);
        node.put("effectiveProbability", PredictionMath.round3(pEff));
        node.put("cumulativeCredibility", cumCred);
        node.put("explanation", explanation);
        node.put("x", nx);
        node.put("y", ny);

        // 为每条上游引用生成一条边；溯因时方向是 当前节点 → 上游，正向反之
        List<Map<String, Object>> edges = new ArrayList<>();
        for (String otherId : linkIds) {
            String from = ctx.backward() ? id : otherId;
            String to = ctx.backward() ? otherId : id;
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", "pe_" + from + "_" + to);
            edge.put("from", from);
            edge.put("to", to);
            edge.put("label", ctx.backward() ? "可能导致" : "推演");
            edge.put("source", "predicted");
            // rule_driven 表示这条因果是基于本体图谱中的预定义规则，前端会用粉色高亮
            edge.put("rule_driven", ruleId != null);
            if (ruleId != null) edge.put("ruleId", ruleId);
            edges.add(edge);
        }

        // chainItem 是给 Scenario.chain 持久化用的扁平视图，便于前端时间轴 / 详情面板展示
        Map<String, Object> chainItem = new LinkedHashMap<>();
        chainItem.put("step", step);
        chainItem.put("nodeId", id);
        chainItem.put("label", label);
        chainItem.put("type", type);
        chainItem.put("triggeredBy", linkIds);
        chainItem.put("ruleId", ruleId);
        chainItem.put("explanation", explanation);
        chainItem.put("confidence", confidence);
        chainItem.put("effectiveProbability", PredictionMath.round3(pEff));
        chainItem.put("cumulativeCredibility", cumCred);

        StepBuildResult sr = new StepBuildResult();
        sr.node = node;
        sr.edges = edges;
        sr.chainItem = chainItem;
        sr.step = step;
        return sr;
    }
}

package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.model.Constraint;
import com.tuiyan.backend.model.PredictRequest;
import com.tuiyan.backend.model.PredictionDag;
import com.tuiyan.backend.model.Scenario;
import com.tuiyan.backend.support.IdSaltRewriter;
import com.tuiyan.backend.support.PredictionMath;
import com.tuiyan.backend.support.SsePushUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 推演编排服务：负责整条推演流程的串联。
 * <p>主要职责：
 * <ul>
 *   <li>调用 {@link LlmService} 让大模型产出推演链（chain）；</li>
 *   <li>按链上每一步构造图谱节点 / 边，并计算 effectiveProbability、cumulativeCredibility 等指标；</li>
 *   <li>通过 SSE（Server-Sent Events）以 step 事件分步推送给前端，实现"逐步生长"的可视化效果；</li>
 *   <li>把最终结果包装为 {@link Scenario} 持久化到本地文件。</li>
 * </ul>
 * 该类是线程安全无关的：每次推演由 controller 在独立线程中调用 {@link #run}，互不干扰。
 */
@Service
public class PredictionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PredictionOrchestrator.class);

    // 节点在画布上的水平 / 垂直步距（像素），用于按推演步数自动排布
    private static final double X_STEP = 220;
    private static final double Y_STEP = 100;
    // 每推送一个 step 事件后强制 sleep，制造"逐步生长"的视觉节奏；前端无需额外节流
    private static final long STEP_DELAY_MS = 220;

    private final LlmService llmService;
    private final ScenarioService scenarioService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PredictionOrchestrator(LlmService llmService, ScenarioService scenarioService) {
        this.llmService = llmService;
        this.scenarioService = scenarioService;
    }

    /**
     * 推演主入口。由 controller 在异步 executor 中调用。
     * <p>整体流程：
     * <ol>
     *   <li>判定意图（forward 正向 / backward 溯因）与是否为 fork 分支；</li>
     *   <li>调用 LLM 拿到 chain JSON，遍历每一步构造节点 / 边并通过 SSE 推送；</li>
     *   <li>处理 what-if 约束（block 剪枝 / probability 先验），实时累积每个节点的概率；</li>
     *   <li>构造 {@link Scenario} 并落盘，最后发送 complete / error 事件结束流。</li>
     * </ol>
     *
     * @param req       前端推演请求（包含种子、步数、约束等）
     * @param emitter   SSE 发射器，由 controller 创建
     * @param cancelled 由 {@link SsePushUtils.CancellableEmitter} 维护的取消标志，
     *                  controller 检测到客户端断开会置为 true，本方法在多处轮询提前退出
     */
    public void run(PredictRequest req, SseEmitter emitter, AtomicBoolean cancelled) {

        try {
            // intent 仅接受 forward / backward，其它值一律按 forward 处理；backward 表示溯因（往前找原因）
            String intent = "backward".equalsIgnoreCase(req.getIntent()) ? "backward" : "forward";
            boolean backward = "backward".equals(intent);

            // 若指定了 parentBranchId，则本次推演是从已有分支 fork 出来的，需要给节点 id 加盐避免与父分支冲突
            boolean isFork = req.getParentBranchId() != null && !req.getParentBranchId().isBlank();
            long now = System.currentTimeMillis();
            String scenarioId = "sc_" + now;
            // 用毫秒时间戳的 36 进制作盐，既短又能保证同一秒发起的 fork 互不冲突
            String idSalt = isFork ? Long.toString(now, 36) : "";

            if (cancelled.get()) return;
            // P1-8：构造 prompt 快照，写入 Scenario.rawPrompt，便于事后审计 LLM 实际看到了什么
            com.tuiyan.backend.service.LlmService.PredictPromptArtifact promptArtifact = llmService.buildPredictPrompt(req);
            JsonNode result = llmService.predictChain(req);
            JsonNode chain = result.path("chain");
            if (!chain.isArray() || chain.isEmpty()) {
                SsePushUtils.safeSend(emitter, cancelled, "error", "LLM 未返回有效推演链");
                if (!cancelled.get()) emitter.complete();
                return;
            }

            // 收集本批 chain 内部的所有 id：IdSaltRewriter 需要据此区分"本批新增节点"与"对祖先节点的引用"，
            // 只有本批新增节点的 id 才会被加盐
            Set<String> currentChainIds = new HashSet<>();
            for (JsonNode item : chain) {
                String raw = item.path("id").asText("");
                if (!raw.isEmpty()) currentChainIds.add(raw);
            }

            // 计算推演节点在画布上的起始坐标（一般在种子节点附近）
            double[] origin = PredictionMath.computeOrigin(req);
            double baseX = origin[0];
            double baseY = origin[1];
            // 正向推演节点向右扩展，溯因则向左扩展
            int direction = backward ? -1 : 1;

            // what-if 约束：block 模式收集要剪枝的节点 id
            Set<String> blockedIds = new HashSet<>();
            // P1-10：probability 模式 → nodeId -> 先验概率（限制在 [0, 1]）
            Map<String, Double> priorMap = new HashMap<>();
            if (req.getConstraints() != null) {
                for (Constraint c : req.getConstraints()) {
                    if (c == null || c.getNodeId() == null) continue;
                    if ("block".equalsIgnoreCase(c.getMode())) {
                        blockedIds.add(c.getNodeId());
                    } else if ("probability".equalsIgnoreCase(c.getMode()) && c.getProbability() != null) {
                        double p = Math.max(0.0, Math.min(1.0, c.getProbability()));
                        priorMap.put(c.getNodeId(), p);
                    }
                }
            }

            // effProb：每个节点的"有效概率"（联合上游概率后的结果），用于级联计算
            Map<String, Double> effProb = new HashMap<>();
            // P1-10：把用户先验作为现有节点的初值，影响下游联合概率
            effProb.putAll(priorMap);
            // cumCredibility：从 seed 到当前节点的累积可信度，区别于单步置信度
            Map<String, Double> cumCredibility = new HashMap<>();
            // 同步把先验也作为累积可信度入口，否则下游 cumCred 取 maxUpstream=1.0 会失真
            cumCredibility.putAll(priorMap);
            List<Map<String, Object>> predictedNodes = new ArrayList<>();
            List<Map<String, Object>> predictedEdges = new ArrayList<>();
            List<Map<String, Object>> chainList = new ArrayList<>();
            // 每一步的节点数计数器，用于同步槽位（slot）排布，避免节点重叠
            Map<Integer, Integer> perStepCount = new HashMap<>();
            // 记录被剪枝节点的明细，最终通过 notice 事件发回前端做提示
            List<Map<String, Object>> pruneDetails = new ArrayList<>();
            int prunedCount = 0;

            int stepIndex = 0;
            for (JsonNode item : chain) {
                if (cancelled.get()) return;
                stepIndex++;
                StepBuildResult sr = buildStep(item, stepIndex, idSalt, currentChainIds,
                        backward, blockedIds, effProb, cumCredibility, perStepCount, baseX, baseY, direction);
                if (sr == null) {
                    // buildStep 返回 null = 当前节点所有上游都被 block，连带剪枝；把自身 id 也加入 blocked，
                    // 防止后续节点继续引用它（否则会出现孤儿节点）
                    String rawId = item.path("id").asText("p_" + stepIndex);
                    String prunedId = IdSaltRewriter.applyPredictionIdSalt(rawId, idSalt, currentChainIds);
                    String prunedLabel = item.path("label").asText("预测" + stepIndex);
                    blockedIds.add(prunedId);
                    prunedCount++;
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("nodeId", prunedId);
                    detail.put("label", prunedLabel);
                    detail.put("reason", "上游节点均被 block 约束剪枝");
                    pruneDetails.add(detail);
                    continue;
                }
                predictedNodes.add(sr.node);
                predictedEdges.addAll(sr.edges);
                chainList.add(sr.chainItem);

                // 组装 SSE step 事件 payload；前端 usePrediction.onStep 会按此结构解析
                ObjectNode stepEvent = objectMapper.createObjectNode();
                stepEvent.put("step", sr.step);
                stepEvent.put("intent", intent);
                stepEvent.set("node", objectMapper.valueToTree(sr.node));
                stepEvent.set("edges", objectMapper.valueToTree(sr.edges));
                stepEvent.set("chain", objectMapper.valueToTree(sr.chainItem));

                if (cancelled.get()) return;
                if (!SsePushUtils.safeSend(emitter, cancelled, "step", objectMapper.writeValueAsString(stepEvent))) {
                    return;
                }
                try {
                    // 节流：让前端能感知到逐步生长，而非一次性涌出全部节点
                    Thread.sleep(STEP_DELAY_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (cancelled.get()) return;
            Scenario s = buildScenario(req, intent, backward, now, scenarioId, chain.size(),
                    predictedNodes, predictedEdges, chainList);
            // P1-8：把 system + user prompt 拼成可读快照写入 rawPrompt，方便事后排查 LLM 行为
            s.setRawPrompt("=== SYSTEM ===\n" + promptArtifact.system()
                    + "\n\n=== USER ===\n" + promptArtifact.user());

            // 若有剪枝发生，发一个 notice 事件让前端 toast 提示用户
            if (prunedCount > 0) {
                ObjectNode note = objectMapper.createObjectNode();
                note.put("type", "pruned");
                note.put("count", prunedCount);
                note.put("message", "已根据 what-if 约束剪枝 " + prunedCount + " 个预测节点");
                note.set("details", objectMapper.valueToTree(pruneDetails));
                SsePushUtils.safeSend(emitter, cancelled, "notice", objectMapper.writeValueAsString(note));
            }

            scenarioService.save(s);
            // complete 事件携带完整 Scenario，前端用它创建新分支
            SsePushUtils.safeSend(emitter, cancelled, "complete", objectMapper.writeValueAsString(s));
            if (!cancelled.get()) emitter.complete();
        } catch (Exception e) {
            log.warn("runPrediction failed: {}", e.toString(), e);
            SsePushUtils.safeSend(emitter, cancelled, "error", e.getMessage() == null ? "推演失败" : e.getMessage());
            try {
                if (!cancelled.get()) emitter.completeWithError(e);
            } catch (Exception completeErr) {
                log.warn("completeWithError after failure also failed: {}", completeErr.toString());
            }
        }
    }

    /** 单步构造的结果容器；当所有上游均被 block 时，{@link #buildStep} 返回 null 触发剪枝。 */
    private static class StepBuildResult {
        Map<String, Object> node;
        List<Map<String, Object>> edges;
        Map<String, Object> chainItem;
        int step;
    }

    /**
     * 把 LLM 返回的单步 chain item 转换为画布节点、边、chain 记录。
     * <p>核心计算：
     * <ul>
     *   <li>effectiveProbability：正向时 = confidence × (1 - ∏(1 - 上游 effProb))，溯因或无上游时直接取 confidence；</li>
     *   <li>cumulativeCredibility：effProb × max(上游 cumCred)，反映从 seed 一路走到此节点的整体可信度。</li>
     * </ul>
     * 若 rawLinkIds 非空但被全部剪光，返回 null 触发剪枝。
     */
    private StepBuildResult buildStep(JsonNode item, int stepIndex, String idSalt, Set<String> currentChainIds,
                                      boolean backward, Set<String> blockedIds, Map<String, Double> effProb,
                                      Map<String, Double> cumCredibility,
                                      Map<Integer, Integer> perStepCount, double baseX, double baseY, int direction) {
        String rawId = item.path("id").asText("p_" + stepIndex);
        // 给本批新增节点 id 加盐，保证 fork 时与父分支不冲突；引用祖先节点则保持原 id
        String id = IdSaltRewriter.applyPredictionIdSalt(rawId, idSalt, currentChainIds);
        String label = item.path("label").asText("预测" + stepIndex);
        String type = item.path("type").asText("event");
        String ruleId = item.path("rule_id").isNull() ? null : item.path("rule_id").asText(null);
        String explanation = item.path("explanation").asText("");
        double confidence = item.path("confidence").asDouble(0.6);
        int step = item.path("step").asInt(stepIndex);

        // 溯因取 leads_to（"这个节点导致什么"）；正向取 triggered_by（"被什么触发"）
        // 兼容 LLM 偶尔字段反着写的情况
        JsonNode linkNode = backward
                ? (item.has("leads_to") ? item.get("leads_to") : item.path("triggered_by"))
                : (item.has("triggered_by") ? item.get("triggered_by") : item.path("leads_to"));

        List<String> rawLinkIds = new ArrayList<>();
        if (linkNode != null && linkNode.isArray()) {
            for (JsonNode t : linkNode) {
                rawLinkIds.add(IdSaltRewriter.applyPredictionIdSalt(t.asText(), idSalt, currentChainIds));
            }
        }
        // 过滤掉已被 block 的上游
        List<String> linkIds = new ArrayList<>();
        for (String lid : rawLinkIds) {
            if (!blockedIds.contains(lid)) linkIds.add(lid);
        }
        // LLM 原本声明了上游，但全被剪光 → 本节点失去依据，整体剪枝
        if (!rawLinkIds.isEmpty() && linkIds.isEmpty()) {
            return null;
        }

        // 同一 step 中节点垂直堆叠：slot 用于上下错位避免重叠
        int slot = perStepCount.getOrDefault(step, 0);
        perStepCount.put(step, slot + 1);
        double nx = baseX + direction * step * X_STEP;
        double ny = baseY + (slot - 0.5) * Y_STEP;

        // 计算 effectiveProbability（有效概率）
        double pEff;
        if (backward || linkIds.isEmpty()) {
            // 溯因或无上游：取节点自身置信度，不级联
            pEff = PredictionMath.clamp01(confidence);
        } else {
            // 正向：用 noisy-OR 公式合并多个上游概率（任一上游触发即可触发本节点）
            // notOr = ∏(1 - p_i)，即"所有上游都不触发"的概率
            double notOr = 1.0;
            for (String pid : linkIds) {
                double pp = effProb.containsKey(pid) ? effProb.get(pid) : 1.0;
                notOr *= (1.0 - PredictionMath.clamp01(pp));
            }
            // 最终概率 = 自身置信度 × (至少一个上游触发的概率)
            pEff = PredictionMath.clamp01(confidence) * (1.0 - notOr);
        }
        effProb.put(id, pEff);

        // 计算累积置信度：与 effProb 不同，cumCred 反映"链路最薄弱的环节"，
        // 取上游 cumCred 的最大值（最可靠路径），再乘以本节点 effProb
        double cumCred;
        if (linkIds.isEmpty()) {
            cumCred = PredictionMath.round3(pEff);
        } else {
            double maxUpstream = 0.0;
            for (String pid : linkIds) {
                double up = cumCredibility.containsKey(pid) ? cumCredibility.get(pid) : 1.0;
                if (up > maxUpstream) maxUpstream = up;
            }
            cumCred = PredictionMath.round3(pEff * maxUpstream);
        }
        cumCredibility.put(id, cumCred);

        // 组装节点对象（前端 OntologyNode 形状），source=predicted 用来与 trunk 节点区分
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("label", label);
        node.put("type", type);
        node.put("source", "predicted");
        node.put("predictedStep", step);
        node.put("predictedIntent", backward ? "backward" : "forward");
        node.put("confidence", confidence);
        node.put("effectiveProbability", PredictionMath.round3(pEff));
        node.put("cumulativeCredibility", cumCred);
        node.put("explanation", explanation);
        node.put("x", nx);
        node.put("y", ny);

        // 为每条上游引用生成一条边；溯因时方向是 当前节点 → 上游，正向反之
        List<Map<String, Object>> edges = new ArrayList<>();
        for (String otherId : linkIds) {
            String from = backward ? id : otherId;
            String to = backward ? otherId : id;
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", "pe_" + from + "_" + to);
            edge.put("from", from);
            edge.put("to", to);
            edge.put("label", backward ? "可能导致" : "推演");
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

    /**
     * 把推演结果打包为 {@link Scenario}（分支）用于持久化。
     * 若 req.name 为空则自动生成"溯因·种子标签 · MM-dd HH:mm"格式的默认名称。
     */
    private Scenario buildScenario(PredictRequest req, String intent, boolean backward, long now,
                                   String scenarioId, int chainSize,
                                   List<Map<String, Object>> predictedNodes,
                                   List<Map<String, Object>> predictedEdges,
                                   List<Map<String, Object>> chainList) {
        Scenario s = new Scenario();
        s.setId(scenarioId);
        s.setModelId(req.getModelId());
        s.setParentBranchId(req.getParentBranchId());
        s.setCreatedAt(now);
        s.setIntent(intent);
        s.setSeeds(req.getSeeds());
        s.setSteps(req.getSteps() == null ? chainSize : req.getSteps());
        s.setPrompt(req.getPrompt());

        String name = req.getName();
        if (name == null || name.isBlank()) {
            String seedLabel = PredictionMath.lookupSeedLabel(req);
            String prefix = backward ? "溯因·" : "";
            name = prefix + (seedLabel != null ? seedLabel : "推演") + " · "
                    + new SimpleDateFormat("MM-dd HH:mm").format(new Date());
        }
        s.setName(name);

        // 把推演链同时挂在 dag 和 chain 上：dag 给画布渲染，chain 给时间轴 / 详情用
        PredictionDag dag = new PredictionDag();
        dag.setIntent(intent);
        dag.setNodes(predictedNodes);
        dag.setEdges(predictedEdges);
        dag.setChain(chainList);
        dag.setConstraints(req.getConstraints());
        s.setDag(dag);
        s.setChain(chainList);
        return s;
    }
}

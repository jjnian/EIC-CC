package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.model.Constraint;
import com.tuiyan.backend.model.PredictRequest;
import com.tuiyan.backend.model.Scenario;
import com.tuiyan.backend.service.llm.GraphPromptBuilder;
import com.tuiyan.backend.support.IdSaltRewriter;
import com.tuiyan.backend.support.PredictionMath;
import com.tuiyan.backend.support.SsePushUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
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
 *   <li>调用 {@link PredictLlmService} 让大模型产出推演链（chain）；</li>
 *   <li>按链上每一步构造图谱节点 / 边，并计算 effectiveProbability、cumulativeCredibility 等指标；</li>
 *   <li>通过 SSE（Server-Sent Events）以 step 事件分步推送给前端，实现"逐步生长"的可视化效果；</li>
 *   <li>把最终结果包装为 {@link Scenario} 持久化到本地文件。</li>
 * </ul>
 * 该类是线程安全无关的：每次推演由 controller 在独立线程中调用 {@link #run}，互不干扰。
 */
@Service
public class PredictionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PredictionOrchestrator.class);

    // 每推送一个 step 事件后强制 sleep，制造"逐步生长"的视觉节奏；前端无需额外节流
    private static final long STEP_DELAY_MS = 220;

    private final PredictLlmService predictLlmService;
    private final ScenarioService scenarioService;
    private final PredictionStepBuilder stepBuilder;
    private final ScenarioAssembler scenarioAssembler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PredictionOrchestrator(PredictLlmService predictLlmService, ScenarioService scenarioService,
                                  PredictionStepBuilder stepBuilder, ScenarioAssembler scenarioAssembler) {
        this.predictLlmService = predictLlmService;
        this.scenarioService = scenarioService;
        this.stepBuilder = stepBuilder;
        this.scenarioAssembler = scenarioAssembler;
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
            GraphPromptBuilder.PredictPromptArtifact promptArtifact = predictLlmService.buildPredictPrompt(req);
            JsonNode result = predictLlmService.predictChain(req);
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

            // 跨步共享上下文：承载每次推演不变的配置 + 跨步就地累积的可变状态
            // （blockedIds / effProb / cumCredibility / perStepCount），由本方法持有并传入 buildStep
            PredictionStepBuilder.Context ctx = new PredictionStepBuilder.Context(
                    idSalt, currentChainIds, backward, baseX, baseY, direction);

            // what-if 约束：block 模式收集要剪枝的节点 id；probability 模式收集先验概率
            if (req.getConstraints() != null) {
                for (Constraint c : req.getConstraints()) {
                    if (c == null || c.getNodeId() == null) continue;
                    if ("block".equalsIgnoreCase(c.getMode())) {
                        ctx.blockedIds().add(c.getNodeId());
                    } else if ("probability".equalsIgnoreCase(c.getMode()) && c.getProbability() != null) {
                        // P1-10：probability 模式 → 先验概率（限制在 [0, 1]）
                        double p = Math.max(0.0, Math.min(1.0, c.getProbability()));
                        // 把用户先验同时作为 effProb（影响下游联合概率）与 cumCredibility 的初值，
                        // 否则下游 cumCred 取 maxUpstream=1.0 会失真
                        ctx.effProb().put(c.getNodeId(), p);
                        ctx.cumCredibility().put(c.getNodeId(), p);
                    }
                }
            }

            List<Map<String, Object>> predictedNodes = new ArrayList<>();
            List<Map<String, Object>> predictedEdges = new ArrayList<>();
            List<Map<String, Object>> chainList = new ArrayList<>();
            // 记录被剪枝节点的明细，最终通过 notice 事件发回前端做提示
            List<Map<String, Object>> pruneDetails = new ArrayList<>();
            int prunedCount = 0;

            int stepIndex = 0;
            for (JsonNode item : chain) {
                if (cancelled.get()) return;
                stepIndex++;
                PredictionStepBuilder.StepBuildResult sr = stepBuilder.buildStep(item, stepIndex, ctx);
                if (sr == null) {
                    // buildStep 返回 null = 当前节点所有上游都被 block，连带剪枝；把自身 id 也加入 blocked，
                    // 防止后续节点继续引用它（否则会出现孤儿节点）
                    String rawId = item.path("id").asText("p_" + stepIndex);
                    String prunedId = IdSaltRewriter.applyPredictionIdSalt(rawId, idSalt, currentChainIds);
                    String prunedLabel = item.path("label").asText("预测" + stepIndex);
                    ctx.blockedIds().add(prunedId);
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
            Scenario s = scenarioAssembler.buildScenario(req, intent, backward, now, scenarioId, chain.size(),
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
}

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
 * 推演编排:执行 LLM 调用 + 构造图节点/边 + SSE 分步推送 + 持久化 Scenario。
 */
@Service
public class PredictionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PredictionOrchestrator.class);

    private static final double X_STEP = 220;
    private static final double Y_STEP = 100;
    private static final long STEP_DELAY_MS = 220;

    private final LlmService llmService;
    private final ScenarioService scenarioService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PredictionOrchestrator(LlmService llmService, ScenarioService scenarioService) {
        this.llmService = llmService;
        this.scenarioService = scenarioService;
    }

    /**
     * 主入口:由 controller 注入的 executor 调用。
     * cancelled 由 controller 通过 CancellableEmitter 装配好后传入。
     */
    public void run(PredictRequest req, SseEmitter emitter, AtomicBoolean cancelled) {

        try {
            String intent = "backward".equalsIgnoreCase(req.getIntent()) ? "backward" : "forward";
            boolean backward = "backward".equals(intent);

            boolean isFork = req.getParentBranchId() != null && !req.getParentBranchId().isBlank();
            long now = System.currentTimeMillis();
            String scenarioId = "sc_" + now;
            String idSalt = isFork ? Long.toString(now, 36) : "";

            if (cancelled.get()) return;
            // P1-8：先构造一份 prompt 文本快照，写入 Scenario，便于事后审计
            com.tuiyan.backend.service.LlmService.PredictPromptArtifact promptArtifact = llmService.buildPredictPrompt(req);
            JsonNode result = llmService.predictChain(req);
            JsonNode chain = result.path("chain");
            if (!chain.isArray() || chain.isEmpty()) {
                SsePushUtils.safeSend(emitter, cancelled, "error", "LLM 未返回有效推演链");
                if (!cancelled.get()) emitter.complete();
                return;
            }

            // 收集本批 chain id,供 IdSaltRewriter 区分本批 vs 祖先引用
            Set<String> currentChainIds = new HashSet<>();
            for (JsonNode item : chain) {
                String raw = item.path("id").asText("");
                if (!raw.isEmpty()) currentChainIds.add(raw);
            }

            double[] origin = PredictionMath.computeOrigin(req);
            double baseX = origin[0];
            double baseY = origin[1];
            int direction = backward ? -1 : 1;

            // what-if block 约束
            Set<String> blockedIds = new HashSet<>();
            // P1-10：probability 约束 → nodeId -> 先验值（0..1）
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

            Map<String, Double> effProb = new HashMap<>();
            // P1-10：把用户先验作为现有节点的 effProb 入口初值，影响下游联合概率
            effProb.putAll(priorMap);
            Map<String, Double> cumCredibility = new HashMap<>();
            // 同步把先验也作为累积可信度入口，否则下游 cumCred 取 maxUpstream=1.0 会失真
            cumCredibility.putAll(priorMap);
            List<Map<String, Object>> predictedNodes = new ArrayList<>();
            List<Map<String, Object>> predictedEdges = new ArrayList<>();
            List<Map<String, Object>> chainList = new ArrayList<>();
            Map<Integer, Integer> perStepCount = new HashMap<>();
            List<Map<String, Object>> pruneDetails = new ArrayList<>();
            int prunedCount = 0;

            int stepIndex = 0;
            for (JsonNode item : chain) {
                if (cancelled.get()) return;
                stepIndex++;
                StepBuildResult sr = buildStep(item, stepIndex, idSalt, currentChainIds,
                        backward, blockedIds, effProb, cumCredibility, perStepCount, baseX, baseY, direction);
                if (sr == null) {
                    // 被剪枝:把本节点 id 也加入 blocked,避免后续引用
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
                    Thread.sleep(STEP_DELAY_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (cancelled.get()) return;
            Scenario s = buildScenario(req, intent, backward, now, scenarioId, chain.size(),
                    predictedNodes, predictedEdges, chainList);
            // P1-8：把 system + user prompt 拼成可读快照写入 rawPrompt
            s.setRawPrompt("=== SYSTEM ===\n" + promptArtifact.system()
                    + "\n\n=== USER ===\n" + promptArtifact.user());

            if (prunedCount > 0) {
                ObjectNode note = objectMapper.createObjectNode();
                note.put("type", "pruned");
                note.put("count", prunedCount);
                note.put("message", "已根据 what-if 约束剪枝 " + prunedCount + " 个预测节点");
                note.set("details", objectMapper.valueToTree(pruneDetails));
                SsePushUtils.safeSend(emitter, cancelled, "notice", objectMapper.writeValueAsString(note));
            }

            scenarioService.save(s);
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

    /** 单步构造结果。被剪枝时返回 null。 */
    private static class StepBuildResult {
        Map<String, Object> node;
        List<Map<String, Object>> edges;
        Map<String, Object> chainItem;
        int step;
    }

    private StepBuildResult buildStep(JsonNode item, int stepIndex, String idSalt, Set<String> currentChainIds,
                                      boolean backward, Set<String> blockedIds, Map<String, Double> effProb,
                                      Map<String, Double> cumCredibility,
                                      Map<Integer, Integer> perStepCount, double baseX, double baseY, int direction) {
        String rawId = item.path("id").asText("p_" + stepIndex);
        String id = IdSaltRewriter.applyPredictionIdSalt(rawId, idSalt, currentChainIds);
        String label = item.path("label").asText("预测" + stepIndex);
        String type = item.path("type").asText("event");
        String ruleId = item.path("rule_id").isNull() ? null : item.path("rule_id").asText(null);
        String explanation = item.path("explanation").asText("");
        double confidence = item.path("confidence").asDouble(0.6);
        int step = item.path("step").asInt(stepIndex);

        JsonNode linkNode = backward
                ? (item.has("leads_to") ? item.get("leads_to") : item.path("triggered_by"))
                : (item.has("triggered_by") ? item.get("triggered_by") : item.path("leads_to"));

        List<String> rawLinkIds = new ArrayList<>();
        if (linkNode != null && linkNode.isArray()) {
            for (JsonNode t : linkNode) {
                rawLinkIds.add(IdSaltRewriter.applyPredictionIdSalt(t.asText(), idSalt, currentChainIds));
            }
        }
        List<String> linkIds = new ArrayList<>();
        for (String lid : rawLinkIds) {
            if (!blockedIds.contains(lid)) linkIds.add(lid);
        }
        if (!rawLinkIds.isEmpty() && linkIds.isEmpty()) {
            return null;
        }

        int slot = perStepCount.getOrDefault(step, 0);
        perStepCount.put(step, slot + 1);
        double nx = baseX + direction * step * X_STEP;
        double ny = baseY + (slot - 0.5) * Y_STEP;

        double pEff;
        if (backward || linkIds.isEmpty()) {
            pEff = PredictionMath.clamp01(confidence);
        } else {
            double notOr = 1.0;
            for (String pid : linkIds) {
                double pp = effProb.containsKey(pid) ? effProb.get(pid) : 1.0;
                notOr *= (1.0 - PredictionMath.clamp01(pp));
            }
            pEff = PredictionMath.clamp01(confidence) * (1.0 - notOr);
        }
        effProb.put(id, pEff);

        // 计算累积置信度:从 seed 到当前步的综合可信度
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
            edge.put("rule_driven", ruleId != null);
            if (ruleId != null) edge.put("ruleId", ruleId);
            edges.add(edge);
        }

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

package com.tuiyan.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.ResourceNotFoundException;
import com.tuiyan.backend.model.NodeExplanation;
import com.tuiyan.backend.model.Scenario;
import com.tuiyan.backend.repository.ScenarioRepository;
import com.tuiyan.backend.support.SsePushUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P1-7：对推演节点生成三段式解释（依据 / 假设 / 反例），缓存到 scenario_node_explanation 表。
 * <p>三段分别通过 SSE 的 chunk 事件推送给前端浮动面板，避免用户等待完整 JSON 才看到内容。
 * 缓存命中时仍然走分段推送，让前端逻辑可以统一处理。
 */
@Service
public class ScenarioExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioExplanationService.class);

    private final ScenarioService scenarioService;
    private final ScenarioRepository scenarioRepository;
    private final ExplainLlmService explainLlmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScenarioExplanationService(ScenarioService scenarioService,
                                      ScenarioRepository scenarioRepository,
                                      ExplainLlmService explainLlmService) {
        this.scenarioService = scenarioService;
        this.scenarioRepository = scenarioRepository;
        this.explainLlmService = explainLlmService;
    }

    /**
     * 入口：在 controller 提供的 executor 上跑。
     * @param scenarioId   分支 id
     * @param nodeId       预测节点 id
     * @param modelOverride 可选模型名覆盖
     * @param configId      可选预置配置 id
     * @param forceRegenerate 是否跳过缓存重新生成
     */
    public void explain(String scenarioId, String nodeId,
                        String modelOverride, String configId, boolean forceRegenerate,
                        SseEmitter emitter, AtomicBoolean cancelled) {
        try {
            Scenario scenario = scenarioService.get(scenarioId);
            if (scenario == null) {
                throw new ResourceNotFoundException("Scenario not found: " + scenarioId);
            }
            // 在 chain 中查找该节点；找不到说明 nodeId 不属于这条分支
            Map<String, Object> chainStep = findChainStep(scenario, nodeId);
            if (chainStep == null) {
                throw new ResourceNotFoundException("Node not in scenario chain: " + nodeId);
            }

            // 缓存命中且未强制重生 → 直接走推送通道；带上 cached=true 标记给前端做区分（如 UI 上提示"已缓存"）
            if (!forceRegenerate && scenario.getDag() != null
                    && scenario.getDag().getExplanations() != null
                    && scenario.getDag().getExplanations().get(nodeId) != null) {
                NodeExplanation cached = scenario.getDag().getExplanations().get(nodeId);
                sendChunks(emitter, cancelled, cached);
                SsePushUtils.safeSend(emitter, cancelled, "complete",
                        objectMapper.writeValueAsString(Map.of("explanation", cached, "cached", true)));
                if (!cancelled.get()) emitter.complete();
                return;
            }

            // 构造 user prompt：当前步骤 + 触发节点 + 前置链路摘要
            String userPrompt = buildUserPrompt(scenario, chainStep);

            if (cancelled.get()) return;
            ExplainLlmService.ExplainResult result = explainLlmService.explainNode(userPrompt, modelOverride, configId);
            JsonNode root = result.json();
            String evidence = root.path("evidence").asText("").trim();
            String assumptions = root.path("assumptions").asText("").trim();
            String counterexamples = root.path("counterexamples").asText("").trim();

            if (evidence.isEmpty() && assumptions.isEmpty() && counterexamples.isEmpty()) {
                throw new RuntimeException("LLM 返回的解释为空");
            }

            NodeExplanation explanation = new NodeExplanation();
            explanation.setEvidence(evidence);
            explanation.setAssumptions(assumptions);
            explanation.setCounterexamples(counterexamples);
            explanation.setGeneratedAt(System.currentTimeMillis());
            explanation.setModelName(result.modelName());

            // 分段推送：先发完三段 chunk，再发 complete；让前端 UI 能按字段渐进渲染
            sendChunks(emitter, cancelled, explanation);

            // 写回数据库；写盘失败不影响本次返回结果，仅记日志
            try {
                scenarioRepository.upsertExplanation(scenarioId, nodeId, explanation);
            } catch (Exception ex) {
                log.warn("save explanation cache failed for scenario={}: {}", scenarioId, ex.toString());
            }

            if (!cancelled.get()) {
                SsePushUtils.safeSend(emitter, cancelled, "complete",
                        objectMapper.writeValueAsString(Map.of("explanation", explanation, "cached", false)));
                emitter.complete();
            }
        } catch (ResourceNotFoundException nf) {
            // 资源类异常给前端可读的 error 事件，正常 complete 关闭流
            SsePushUtils.safeSend(emitter, cancelled, "error", nf.getMessage());
            if (!cancelled.get()) emitter.complete();
        } catch (Exception ex) {
            log.warn("explain failed scenario={} node={}: {}", scenarioId, nodeId, ex.toString(), ex);
            SsePushUtils.safeSend(emitter, cancelled, "error",
                    ex.getMessage() == null ? "解释生成失败" : ex.getMessage());
            try {
                if (!cancelled.get()) emitter.completeWithError(ex);
            } catch (Exception completeErr) {
                log.warn("completeWithError failed: {}", completeErr.toString());
            }
        }
    }

    /** 在 dag.chain（新结构）或顶层 chain（v0.5 遗留）中查找指定 nodeId 的步骤。 */
    private Map<String, Object> findChainStep(Scenario scenario, String nodeId) {
        List<Map<String, Object>> chain = scenario.getDag() != null ? scenario.getDag().getChain() : scenario.getChain();
        if (chain == null) return null;
        for (Map<String, Object> step : chain) {
            if (nodeId.equals(String.valueOf(step.get("nodeId")))) return step;
        }
        return null;
    }

    /**
     * 构造 LLM 的 user prompt：把推演方向、当前步骤、上游节点、前置链路拼成结构化文本。
     * <p>设计要点：把"该步骤的因果上下文"完整暴露给 LLM，让它能给出针对性的依据 / 假设 / 反例，
     * 而不是空泛地分析节点本身。
     */
    private String buildUserPrompt(Scenario scenario, Map<String, Object> step) {
        StringBuilder sb = new StringBuilder();
        String intent = scenario.getIntent() != null ? scenario.getIntent() : "forward";
        boolean backward = "backward".equalsIgnoreCase(intent);

        sb.append("推演方向：").append(backward ? "溯因 (Backward)" : "正向 (Forward)").append("\n");
        if (scenario.getPrompt() != null && !scenario.getPrompt().isBlank()) {
            sb.append("场景说明：").append(scenario.getPrompt()).append("\n");
        }
        sb.append("\n当前步骤：\n");
        sb.append("  - id: ").append(step.get("nodeId")).append("\n");
        sb.append("  - 标签: ").append(step.get("label")).append("\n");
        sb.append("  - 类型: ").append(step.get("type")).append("\n");
        if (step.get("confidence") != null) {
            sb.append("  - 当前 confidence: ").append(step.get("confidence")).append("\n");
        }
        if (step.get("ruleId") != null) {
            sb.append("  - 关联规则: ").append(step.get("ruleId")).append("\n");
        }
        if (step.get("explanation") != null) {
            sb.append("  - 已有简要说明: ").append(step.get("explanation")).append("\n");
        }

        // 触发节点（forward: triggered_by；backward: leads_to 用同一字段名 triggeredBy 存）
        Object triggeredBy = step.get("triggeredBy");
        if (triggeredBy instanceof List<?> list && !list.isEmpty()) {
            sb.append("\n").append(backward ? "导致下游节点：" : "由以下节点触发：").append("\n");
            for (Object id : list) {
                String upLabel = lookupLabel(scenario, String.valueOf(id));
                sb.append("  - ").append(id).append(upLabel == null ? "" : " (" + upLabel + ")").append("\n");
            }
        }

        // 前置链路：本步 step 数之前的所有 step 简要列出，让 LLM 看到"从 seed 到当前"的完整因果路径
        List<Map<String, Object>> chain = scenario.getDag() != null
                ? scenario.getDag().getChain() : scenario.getChain();
        if (chain != null) {
            int curStep = step.get("step") instanceof Number ? ((Number) step.get("step")).intValue() : -1;
            List<String> prev = new ArrayList<>();
            for (Map<String, Object> s : chain) {
                int n = s.get("step") instanceof Number ? ((Number) s.get("step")).intValue() : -1;
                if (n > 0 && n < curStep) {
                    prev.add("step" + n + " " + s.get("label"));
                }
            }
            if (!prev.isEmpty()) {
                sb.append("\n前置因果链：\n");
                for (String p : prev) sb.append("  - ").append(p).append("\n");
            }
        }

        sb.append("\n请按 schema 返回 JSON，对上述步骤给出三段式解释（依据/假设/反例）。");
        return sb.toString();
    }

    /** 从 dag.nodes 中按 id 查 label，用于上游引用的可读性增强（"id (label)"）。 */
    private String lookupLabel(Scenario scenario, String nodeId) {
        if (scenario.getDag() != null && scenario.getDag().getNodes() != null) {
            for (Map<String, Object> n : scenario.getDag().getNodes()) {
                if (nodeId.equals(String.valueOf(n.get("id")))) {
                    Object lb = n.get("label");
                    return lb == null ? null : String.valueOf(lb);
                }
            }
        }
        return null;
    }

    /** 把三段内容拆成 3 个 chunk 事件发送，让前端能渐进渲染。 */
    private void sendChunks(SseEmitter emitter, AtomicBoolean cancelled, NodeExplanation explanation) {
        sendField(emitter, cancelled, "evidence", explanation.getEvidence());
        sendField(emitter, cancelled, "assumptions", explanation.getAssumptions());
        sendField(emitter, cancelled, "counterexamples", explanation.getCounterexamples());
    }

    private void sendField(SseEmitter emitter, AtomicBoolean cancelled, String field, String text) {
        if (cancelled.get()) return;
        if (text == null) text = "";
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("field", field);
        payload.put("text", text);
        try {
            SsePushUtils.safeSend(emitter, cancelled, "chunk", objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.warn("serialize chunk failed: {}", e.toString());
        }
    }
}

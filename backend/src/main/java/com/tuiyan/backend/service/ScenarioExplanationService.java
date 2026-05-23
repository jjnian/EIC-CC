package com.tuiyan.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.ResourceNotFoundException;
import com.tuiyan.backend.model.NodeExplanation;
import com.tuiyan.backend.model.Scenario;
import com.tuiyan.backend.support.SsePushUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P1-7：对推演节点生成三段式解释（依据/假设/反例），缓存到 Scenario.dag.explanations。
 * 该服务通过 SSE 将三段内容分别推送给前端浮动面板。
 */
@Service
public class ScenarioExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioExplanationService.class);

    private final ScenarioService scenarioService;
    private final LlmService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScenarioExplanationService(ScenarioService scenarioService, LlmService llmService) {
        this.scenarioService = scenarioService;
        this.llmService = llmService;
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
            // 在 chain 中查找该节点
            Map<String, Object> chainStep = findChainStep(scenario, nodeId);
            if (chainStep == null) {
                throw new ResourceNotFoundException("Node not in scenario chain: " + nodeId);
            }

            // 缓存命中且未强制重生 → 直接返回
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
            LlmService.ExplainResult result = llmService.explainNode(userPrompt, modelOverride, configId);
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

            // 分段推送（让前端能按字段渲染）
            sendChunks(emitter, cancelled, explanation);

            // 写回 Scenario 缓存
            if (scenario.getDag() != null) {
                Map<String, NodeExplanation> map = scenario.getDag().getExplanations();
                if (map == null) {
                    map = new HashMap<>();
                    scenario.getDag().setExplanations(map);
                }
                map.put(nodeId, explanation);
                try {
                    scenarioService.save(scenario);
                } catch (IOException ioe) {
                    log.warn("save explanation cache failed for scenario={}: {}", scenarioId, ioe.toString());
                }
            }

            if (!cancelled.get()) {
                SsePushUtils.safeSend(emitter, cancelled, "complete",
                        objectMapper.writeValueAsString(Map.of("explanation", explanation, "cached", false)));
                emitter.complete();
            }
        } catch (ResourceNotFoundException nf) {
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

    private Map<String, Object> findChainStep(Scenario scenario, String nodeId) {
        List<Map<String, Object>> chain = scenario.getDag() != null ? scenario.getDag().getChain() : scenario.getChain();
        if (chain == null) return null;
        for (Map<String, Object> step : chain) {
            if (nodeId.equals(String.valueOf(step.get("nodeId")))) return step;
        }
        return null;
    }

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

        // 前置链路：取本步之前的所有 step 简要列出
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

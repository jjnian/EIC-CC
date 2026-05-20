package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.model.Constraint;
import com.tuiyan.backend.model.PredictRequest;
import com.tuiyan.backend.model.PredictionDag;
import com.tuiyan.backend.model.Scenario;
import com.tuiyan.backend.service.LlmService;
import com.tuiyan.backend.service.ScenarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private static final Logger log = LoggerFactory.getLogger(ScenarioController.class);

    private final ScenarioService scenarioService;
    private final LlmService llmService;
    private final TaskExecutor predictionExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScenarioController(ScenarioService scenarioService,
                              LlmService llmService,
                              @Qualifier("predictionExecutor") TaskExecutor predictionExecutor) {
        this.scenarioService = scenarioService;
        this.llmService = llmService;
        this.predictionExecutor = predictionExecutor;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String modelId) {
        try {
            return ResponseEntity.ok(scenarioService.listByModel(modelId));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable String id) {
        try {
            Scenario s = scenarioService.get(id);
            if (s == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(s);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        int n = scenarioService.delete(id);
        return ResponseEntity.ok(Map.of("success", n > 0, "count", n));
    }

    @PostMapping("/migrate")
    public ResponseEntity<?> migrate() {
        try {
            return ResponseEntity.ok(scenarioService.migrateAll());
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping
    public SseEmitter predict(@RequestBody PredictRequest req) {
        SseEmitter emitter = new SseEmitter(180_000L);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onTimeout(() -> {
            cancelled.set(true);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("LLM 响应超时（>180s），请检查 LLM 配置或网络后重试"));
            } catch (IOException ioErr) {
                log.warn("emit timeout-error failed: {}", ioErr.toString());
            }
            emitter.complete();
        });
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onError(t -> cancelled.set(true));

        predictionExecutor.execute(() -> runPrediction(req, emitter, cancelled));
        return emitter;
    }

    private void runPrediction(PredictRequest req, SseEmitter emitter, AtomicBoolean cancelled) {
        try {
            String intent = "backward".equalsIgnoreCase(req.getIntent()) ? "backward" : "forward";
            boolean backward = "backward".equals(intent);

            boolean isFork = req.getParentBranchId() != null && !req.getParentBranchId().isBlank();
            long now = System.currentTimeMillis();
            String scenarioId = "sc_" + now;
            String idSalt = isFork ? Long.toString(now, 36) : "";

            if (cancelled.get()) return;
            JsonNode result = llmService.predictChain(req);
            JsonNode chain = result.path("chain");
            if (!chain.isArray() || chain.isEmpty()) {
                safeSend(emitter, cancelled, "error", "LLM 未返回有效推演链");
                if (!cancelled.get()) emitter.complete();
                return;
            }

            // 先扫描本批 chain 的全部 id，构造 currentChainIds —— applyIdSalt 仅对该集合内
            // 形如 ^p_\d+$ 的 raw 值生效；引用祖先节点 id (p<oldSalt>_N) 或 trunk id 时原样保留。
            Set<String> currentChainIds = new HashSet<>();
            for (JsonNode item : chain) {
                String raw = item.path("id").asText("");
                if (!raw.isEmpty()) currentChainIds.add(raw);
            }

            double[] origin = computeOrigin(req);
            double baseX = origin[0];
            double baseY = origin[1];
            double xStep = 220, yStep = 100;
            int direction = backward ? -1 : 1;

            Set<String> blockedIds = new HashSet<>();
            if (req.getConstraints() != null) {
                for (Constraint c : req.getConstraints()) {
                    if (c == null || c.getNodeId() == null) continue;
                    if ("block".equalsIgnoreCase(c.getMode())) blockedIds.add(c.getNodeId());
                }
            }

            Map<String, Double> effProb = new HashMap<>();

            List<Map<String, Object>> predictedNodes = new ArrayList<>();
            List<Map<String, Object>> predictedEdges = new ArrayList<>();
            List<Map<String, Object>> chainList = new ArrayList<>();
            Map<Integer, Integer> perStepCount = new HashMap<>();
            int prunedCount = 0;

            int stepIndex = 0;
            for (JsonNode item : chain) {
                if (cancelled.get()) return;
                stepIndex++;
                String rawId = item.path("id").asText("p_" + stepIndex);
                String id = applyIdSalt(rawId, idSalt, currentChainIds);
                String label = item.path("label").asText("预测" + stepIndex);
                String type = item.path("type").asText("event");
                String ruleId = item.path("rule_id").isNull() ? null : item.path("rule_id").asText(null);
                String explanation = item.path("explanation").asText("");
                double confidence = item.path("confidence").asDouble(0.6);
                int step = item.path("step").asInt(stepIndex);

                JsonNode linkNode = backward
                        ? (item.has("leads_to") ? item.get("leads_to") : item.path("triggered_by"))
                        : (item.has("triggered_by") ? item.get("triggered_by") : item.path("leads_to"));
                ArrayNode links = (linkNode != null && linkNode.isArray())
                        ? (ArrayNode) linkNode : objectMapper.createArrayNode();

                List<String> rawLinkIds = new ArrayList<>();
                for (JsonNode t : links) rawLinkIds.add(applyIdSalt(t.asText(), idSalt, currentChainIds));
                List<String> linkIds = new ArrayList<>();
                for (String lid : rawLinkIds) {
                    if (!blockedIds.contains(lid)) linkIds.add(lid);
                }
                boolean pruneThis = !rawLinkIds.isEmpty() && linkIds.isEmpty();
                if (pruneThis) {
                    blockedIds.add(id);
                    prunedCount++;
                    continue;
                }

                int slot = perStepCount.getOrDefault(step, 0);
                perStepCount.put(step, slot + 1);
                double nx = baseX + direction * step * xStep;
                double ny = baseY + (slot - 0.5) * yStep;

                double pEff;
                if (backward || linkIds.isEmpty()) {
                    pEff = clamp01(confidence);
                } else {
                    double notOr = 1.0;
                    for (String pid : linkIds) {
                        double pp = effProb.containsKey(pid) ? effProb.get(pid) : 1.0;
                        notOr *= (1.0 - clamp01(pp));
                    }
                    double orVal = 1.0 - notOr;
                    pEff = clamp01(confidence) * orVal;
                }
                effProb.put(id, pEff);

                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", id);
                node.put("label", label);
                node.put("type", type);
                node.put("source", "predicted");
                node.put("predictedStep", step);
                node.put("predictedIntent", intent);
                node.put("confidence", confidence);
                node.put("effectiveProbability", round3(pEff));
                node.put("explanation", explanation);
                node.put("x", nx);
                node.put("y", ny);
                predictedNodes.add(node);

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
                    predictedEdges.add(edge);
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
                chainItem.put("effectiveProbability", round3(pEff));
                chainList.add(chainItem);

                ObjectNode stepEvent = objectMapper.createObjectNode();
                stepEvent.put("step", step);
                stepEvent.put("intent", intent);
                stepEvent.set("node", objectMapper.valueToTree(node));
                stepEvent.set("edges", objectMapper.valueToTree(
                        predictedEdges.subList(predictedEdges.size() - linkIds.size(), predictedEdges.size())));
                stepEvent.set("chain", objectMapper.valueToTree(chainItem));

                if (cancelled.get()) return;
                if (!safeSend(emitter, cancelled, "step", objectMapper.writeValueAsString(stepEvent))) {
                    return;
                }

                try { Thread.sleep(220); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (cancelled.get()) return;
            Scenario s = new Scenario();
            s.setId(scenarioId);
            s.setModelId(req.getModelId());
            s.setParentBranchId(req.getParentBranchId());
            s.setCreatedAt(now);
            s.setIntent(intent);
            s.setSeeds(req.getSeeds());
            s.setSteps(req.getSteps() == null ? chain.size() : req.getSteps());
            s.setPrompt(req.getPrompt());

            String name = req.getName();
            if (name == null || name.isBlank()) {
                String seedLabel = lookupSeedLabel(req);
                String prefix = backward ? "溯因·" : "";
                name = prefix + (seedLabel != null ? seedLabel : "推演") + " · "
                        + new java.text.SimpleDateFormat("MM-dd HH:mm").format(new Date());
            }
            s.setName(name);

            PredictionDag dag = new PredictionDag();
            dag.setIntent(intent);
            dag.setNodes(predictedNodes);
            dag.setEdges(predictedEdges);
            dag.setChain(chainList);
            dag.setConstraints(req.getConstraints());
            s.setDag(dag);

            if (prunedCount > 0) {
                ObjectNode note = objectMapper.createObjectNode();
                note.put("type", "pruned");
                note.put("count", prunedCount);
                note.put("message", "已根据 what-if 约束剪枝 " + prunedCount + " 个预测节点");
                safeSend(emitter, cancelled, "notice", objectMapper.writeValueAsString(note));
            }

            s.setChain(chainList);
            scenarioService.save(s);

            safeSend(emitter, cancelled, "complete", objectMapper.writeValueAsString(s));
            if (!cancelled.get()) emitter.complete();
        } catch (Exception e) {
            log.warn("runPrediction failed: {}", e.toString(), e);
            safeSend(emitter, cancelled, "error", e.getMessage() == null ? "推演失败" : e.getMessage());
            try {
                if (!cancelled.get()) emitter.completeWithError(e);
            } catch (Exception completeErr) {
                log.warn("completeWithError after failure also failed: {}", completeErr.toString());
            }
        }
    }

    /**
     * 安全发送：在 cancelled 已置位时静默跳过；send 抛错时记日志并返回 false，调用方应停止后续推送。
     */
    private static boolean safeSend(SseEmitter emitter, AtomicBoolean cancelled, String event, String data) {
        if (cancelled.get()) return false;
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IllegalStateException stateErr) {
            log.warn("emitter already closed when sending {}: {}", event, stateErr.toString());
            cancelled.set(true);
            return false;
        } catch (IOException ioErr) {
            log.warn("emit {} failed (client disconnected?): {}", event, ioErr.toString());
            cancelled.set(true);
            return false;
        }
    }

    /**
     * v0.8 fork id 重写：只对本批 chain 内、严格匹配 ^p_\d+$ 的 raw id 改写为 p<salt>_N。
     * 引用祖先节点的 pXXX_N、trunk 节点 id 原样保留。
     */
    static String applyIdSalt(String raw, String idSalt, Set<String> currentChainIds) {
        if (idSalt == null || idSalt.isEmpty()) return raw;
        if (raw == null) return null;
        if (!raw.matches("p_\\d+")) return raw;
        if (currentChainIds == null || !currentChainIds.contains(raw)) return raw;
        return "p" + idSalt + "_" + raw.substring(2);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private double[] computeOrigin(PredictRequest req) {
        if (req.getSeeds() == null || req.getSeeds().isEmpty() || req.getNodes() == null) {
            return new double[]{800, 300};
        }
        double sx = 0, sy = 0;
        int n = 0;
        for (String sid : req.getSeeds()) {
            for (Map<String, Object> node : req.getNodes()) {
                if (sid.equals(node.get("id"))) {
                    Object xo = node.get("x"), yo = node.get("y");
                    if (xo instanceof Number && yo instanceof Number) {
                        sx += ((Number) xo).doubleValue();
                        sy += ((Number) yo).doubleValue();
                        n++;
                    }
                    break;
                }
            }
        }
        if (n == 0) return new double[]{800, 300};
        return new double[]{sx / n, sy / n};
    }

    private String lookupSeedLabel(PredictRequest req) {
        if (req.getSeeds() == null || req.getSeeds().isEmpty() || req.getNodes() == null) return null;
        String first = req.getSeeds().get(0);
        for (Map<String, Object> n : req.getNodes()) {
            if (first.equals(n.get("id"))) {
                Object lbl = n.get("label");
                return lbl == null ? null : String.valueOf(lbl);
            }
        }
        return null;
    }
}

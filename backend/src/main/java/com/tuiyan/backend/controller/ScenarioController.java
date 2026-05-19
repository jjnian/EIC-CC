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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioService scenarioService;
    private final LlmService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScenarioController(ScenarioService scenarioService, LlmService llmService) {
        this.scenarioService = scenarioService;
        this.llmService = llmService;
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
        return ResponseEntity.ok(Map.of("deleted", n > 0, "count", n));
    }

    @PostMapping
    public SseEmitter predict(@RequestBody PredictRequest req) {
        SseEmitter emitter = new SseEmitter(180_000L);
        emitter.onTimeout(emitter::complete);

        Thread t = new Thread(() -> runPrediction(req, emitter));
        t.setDaemon(true);
        t.start();
        return emitter;
    }

    private void runPrediction(PredictRequest req, SseEmitter emitter) {
        try {
            String intent = "backward".equalsIgnoreCase(req.getIntent()) ? "backward" : "forward";
            boolean backward = "backward".equals(intent);

            // v0.8：从预测分支再次分叉时，新预测节点 id 需加 salt，避免与父分支的 p_N 撞车
            boolean isFork = req.getParentBranchId() != null && !req.getParentBranchId().isBlank();
            long now = System.currentTimeMillis();
            String scenarioId = "sc_" + now;
            String idSalt = isFork ? Long.toString(now, 36) : "";

            JsonNode result = llmService.predictChain(req);
            JsonNode chain = result.path("chain");
            if (!chain.isArray() || chain.isEmpty()) {
                emitter.send(SseEmitter.event().name("error").data("LLM 未返回有效推演链"));
                emitter.complete();
                return;
            }

            // 坐标：forward 向右扩散；backward 向左扩散
            double[] origin = computeOrigin(req);
            double baseX = origin[0];
            double baseY = origin[1];
            double xStep = 220, yStep = 100;
            int direction = backward ? -1 : 1;

            // v0.7：约束 → blockedIds 集合；预测中链路命中 block 节点的整条节点被剪枝（级联）
            // force 仅通过 prompt 暗示给 LLM，无需后端附加处理（trunk 节点本就视作 P=1.0）
            Set<String> blockedIds = new HashSet<>();
            if (req.getConstraints() != null) {
                for (Constraint c : req.getConstraints()) {
                    if (c == null || c.getNodeId() == null) continue;
                    if ("block".equalsIgnoreCase(c.getMode())) blockedIds.add(c.getNodeId());
                }
            }

            // v0.7：用于概率聚合（noisy-OR），predicted -> 计算得到的有效概率
            Map<String, Double> effProb = new HashMap<>();

            List<Map<String, Object>> predictedNodes = new ArrayList<>();
            List<Map<String, Object>> predictedEdges = new ArrayList<>();
            List<Map<String, Object>> chainList = new ArrayList<>();
            Map<Integer, Integer> perStepCount = new HashMap<>();
            int prunedCount = 0;

            int stepIndex = 0;
            for (JsonNode item : chain) {
                stepIndex++;
                String rawId = item.path("id").asText("p_" + stepIndex);
                String id = applyIdSalt(rawId, idSalt);
                String label = item.path("label").asText("预测" + stepIndex);
                String type = item.path("type").asText("event");
                String ruleId = item.path("rule_id").isNull() ? null : item.path("rule_id").asText(null);
                String explanation = item.path("explanation").asText("");
                double confidence = item.path("confidence").asDouble(0.6);
                int step = item.path("step").asInt(stepIndex);

                // forward 读 triggered_by；backward 读 leads_to；二者均回退兼容
                JsonNode linkNode = backward
                        ? (item.has("leads_to") ? item.get("leads_to") : item.path("triggered_by"))
                        : (item.has("triggered_by") ? item.get("triggered_by") : item.path("leads_to"));
                ArrayNode links = (linkNode != null && linkNode.isArray())
                        ? (ArrayNode) linkNode : objectMapper.createArrayNode();

                // 约束剪枝：丢弃所有指向 blocked id 的连接；若 forward 链路全空则整节点剪枝并级联
                // 同时对引用本次新预测节点的 p_N 形式作 salt 重写（祖先预测节点的 id 已是 pXXX_N，不会命中）
                List<String> rawLinkIds = new ArrayList<>();
                for (JsonNode t : links) rawLinkIds.add(applyIdSalt(t.asText(), idSalt));
                List<String> linkIds = new ArrayList<>();
                for (String lid : rawLinkIds) {
                    if (!blockedIds.contains(lid)) linkIds.add(lid);
                }
                boolean pruneThis;
                if (backward) {
                    // backward: predicted 是因，leads_to 是结果；若结果全被 block，该假设失去意义
                    pruneThis = !rawLinkIds.isEmpty() && linkIds.isEmpty();
                } else {
                    // forward: predicted 是果，triggered_by 是因；若所有上游被 block，该预测无依据
                    pruneThis = !rawLinkIds.isEmpty() && linkIds.isEmpty();
                }
                if (pruneThis) {
                    blockedIds.add(id); // 级联：后续引用本节点的预测也会被剪枝
                    prunedCount++;
                    continue;
                }

                int slot = perStepCount.getOrDefault(step, 0);
                perStepCount.put(step, slot + 1);
                double nx = baseX + direction * step * xStep;
                double ny = baseY + (slot - 0.5) * yStep;

                // 概率聚合：
                //  forward — P_eff(N) = confidence × NoisyOR({P_eff(parent_i)})
                //            其中 trunk 节点视为 P=1.0，被 force 的节点同样 P=1.0
                //  backward — P_eff 保留为节点自身 confidence（候选原因的内在置信）
                double pEff;
                if (backward || linkIds.isEmpty()) {
                    pEff = clamp01(confidence);
                } else {
                    double notOr = 1.0;
                    for (String pid : linkIds) {
                        double pp = effProb.containsKey(pid) ? effProb.get(pid) : 1.0; // trunk 默认 1.0
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

                // 边方向：forward = link -> predicted（上游驱动下游）；backward = predicted -> link（原因指向结果）
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
                // 时间线 UI 用 triggeredBy 字段名展示，无论方向；语义由 intent 解释
                chainItem.put("triggeredBy", linkIds);
                chainItem.put("ruleId", ruleId);
                chainItem.put("explanation", explanation);
                chainItem.put("confidence", confidence);
                chainItem.put("effectiveProbability", round3(pEff));
                chainList.add(chainItem);

                // 流式分步推送，给前端动画喘息
                ObjectNode stepEvent = objectMapper.createObjectNode();
                stepEvent.put("step", step);
                stepEvent.put("intent", intent);
                stepEvent.set("node", objectMapper.valueToTree(node));
                stepEvent.set("edges", objectMapper.valueToTree(
                        predictedEdges.subList(predictedEdges.size() - linkIds.size(), predictedEdges.size())));
                stepEvent.set("chain", objectMapper.valueToTree(chainItem));
                emitter.send(SseEmitter.event().name("step")
                        .data(objectMapper.writeValueAsString(stepEvent)));

                try { Thread.sleep(220); } catch (InterruptedException ignored) {}
            }

            // 构建 Scenario：仅保存 dag 增量，不再落 full snapshot
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

            // 若被约束剪枝过，也额外提示前端
            if (prunedCount > 0) {
                try {
                    ObjectNode note = objectMapper.createObjectNode();
                    note.put("type", "pruned");
                    note.put("count", prunedCount);
                    note.put("message", "已根据 what-if 约束剪枝 " + prunedCount + " 个预测节点");
                    emitter.send(SseEmitter.event().name("notice")
                            .data(objectMapper.writeValueAsString(note)));
                } catch (IOException ignored) {}
            }

            // chain 字段保留给老 UI 直接读
            s.setChain(chainList);
            // nodes/edges 不再写完整快照（节省 ~80% 存储）；前端加载时与 trunk 合并

            scenarioService.save(s);

            emitter.send(SseEmitter.event().name("complete")
                    .data(objectMapper.writeValueAsString(s)));
            emitter.complete();
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage() == null ? "推演失败" : e.getMessage()));
            } catch (IOException ignored) {}
            emitter.completeWithError(e);
        }
    }

    /**
     * v0.8 fork id 重写：LLM 总是以 p_N 形式返回新预测节点 id，多级分叉时需要加 salt 隔离。
     * 仅当 raw 严格匹配 ^p_\d+$ 时改写为 p<salt>_N；其他形态（祖先预测节点的 pXXX_N、trunk 节点）原样保留。
     */
    private static String applyIdSalt(String raw, String idSalt) {
        if (idSalt == null || idSalt.isEmpty()) return raw;
        if (raw == null) return raw;
        if (!raw.matches("p_\\d+")) return raw;
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

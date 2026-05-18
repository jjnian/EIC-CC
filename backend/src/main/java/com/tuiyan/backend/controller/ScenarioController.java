package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.model.PredictRequest;
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
        boolean ok = scenarioService.delete(id);
        return ResponseEntity.ok(Map.of("deleted", ok));
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
            JsonNode result = llmService.predictChain(req);
            JsonNode chain = result.path("chain");
            if (!chain.isArray() || chain.isEmpty()) {
                emitter.send(SseEmitter.event().name("error").data("LLM 未返回有效推演链"));
                emitter.complete();
                return;
            }

            // 计算预测节点坐标：以 seeds 重心为基准向右扩散
            double[] origin = computeOrigin(req);
            double baseX = origin[0];
            double baseY = origin[1];
            double xStep = 220, yStep = 100;

            List<Map<String, Object>> predictedNodes = new ArrayList<>();
            List<Map<String, Object>> predictedEdges = new ArrayList<>();
            List<Map<String, Object>> chainList = new ArrayList<>();
            Map<Integer, Integer> perStepCount = new HashMap<>();

            int stepIndex = 0;
            for (JsonNode item : chain) {
                stepIndex++;
                String id = item.path("id").asText("p_" + stepIndex);
                String label = item.path("label").asText("预测" + stepIndex);
                String type = item.path("type").asText("event");
                String ruleId = item.path("rule_id").isNull() ? null : item.path("rule_id").asText(null);
                String explanation = item.path("explanation").asText("");
                double confidence = item.path("confidence").asDouble(0.6);
                int step = item.path("step").asInt(stepIndex);

                ArrayNode triggers = (ArrayNode) (item.has("triggered_by") && item.get("triggered_by").isArray()
                        ? item.get("triggered_by") : objectMapper.createArrayNode());

                int slot = perStepCount.getOrDefault(step, 0);
                perStepCount.put(step, slot + 1);
                double nx = baseX + step * xStep;
                double ny = baseY + (slot - 0.5) * yStep;

                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", id);
                node.put("label", label);
                node.put("type", type);
                node.put("source", "predicted");
                node.put("predictedStep", step);
                node.put("confidence", confidence);
                node.put("explanation", explanation);
                node.put("x", nx);
                node.put("y", ny);
                predictedNodes.add(node);

                List<String> triggerIds = new ArrayList<>();
                for (JsonNode t : triggers) triggerIds.add(t.asText());

                for (String fromId : triggerIds) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("id", "pe_" + id + "_" + fromId);
                    edge.put("from", fromId);
                    edge.put("to", id);
                    edge.put("label", "推演");
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
                chainItem.put("triggeredBy", triggerIds);
                chainItem.put("ruleId", ruleId);
                chainItem.put("explanation", explanation);
                chainItem.put("confidence", confidence);
                chainList.add(chainItem);

                // 流式分步推送，给前端动画喘息
                ObjectNode stepEvent = objectMapper.createObjectNode();
                stepEvent.put("step", step);
                stepEvent.set("node", objectMapper.valueToTree(node));
                stepEvent.set("edges", objectMapper.valueToTree(
                        predictedEdges.subList(predictedEdges.size() - triggerIds.size(), predictedEdges.size())));
                stepEvent.set("chain", objectMapper.valueToTree(chainItem));
                emitter.send(SseEmitter.event().name("step")
                        .data(objectMapper.writeValueAsString(stepEvent)));

                try { Thread.sleep(220); } catch (InterruptedException ignored) {}
            }

            // 构建快照
            Scenario s = new Scenario();
            s.setId("sc_" + System.currentTimeMillis());
            s.setModelId(req.getModelId());
            s.setParentBranchId(req.getParentBranchId());
            s.setCreatedAt(System.currentTimeMillis());
            s.setSeeds(req.getSeeds());
            s.setSteps(req.getSteps() == null ? chain.size() : req.getSteps());
            s.setPrompt(req.getPrompt());

            String name = req.getName();
            if (name == null || name.isBlank()) {
                String seedLabel = lookupSeedLabel(req);
                name = (seedLabel != null ? seedLabel : "推演") + " · "
                        + new java.text.SimpleDateFormat("MM-dd HH:mm").format(new Date());
            }
            s.setName(name);

            List<Map<String, Object>> allNodes = new ArrayList<>();
            if (req.getNodes() != null) allNodes.addAll(req.getNodes());
            allNodes.addAll(predictedNodes);
            s.setNodes(allNodes);

            List<Map<String, Object>> allEdges = new ArrayList<>();
            if (req.getEdges() != null) allEdges.addAll(req.getEdges());
            allEdges.addAll(predictedEdges);
            s.setEdges(allEdges);

            s.setChain(chainList);

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

package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.PredictRequest;
import com.tuiyan.backend.model.Scenario;
import com.tuiyan.backend.model.dto.ExplainRequest;
import com.tuiyan.backend.model.dto.RawPromptResponse;
import com.tuiyan.backend.model.dto.SuccessCountResponse;
import com.tuiyan.backend.service.PredictionOrchestrator;
import com.tuiyan.backend.service.ScenarioExplanationService;
import com.tuiyan.backend.service.ScenarioService;
import com.tuiyan.backend.support.SsePushUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioService scenarioService;
    private final PredictionOrchestrator predictionOrchestrator;
    private final ScenarioExplanationService explanationService;
    private final TaskExecutor predictionExecutor;

    public ScenarioController(ScenarioService scenarioService,
                              PredictionOrchestrator predictionOrchestrator,
                              ScenarioExplanationService explanationService,
                              @Qualifier("predictionExecutor") TaskExecutor predictionExecutor) {
        this.scenarioService = scenarioService;
        this.predictionOrchestrator = predictionOrchestrator;
        this.explanationService = explanationService;
        this.predictionExecutor = predictionExecutor;
    }

    @GetMapping
    public ResponseEntity<List<Scenario>> list(@RequestParam(required = false) String modelId) throws IOException {
        return ResponseEntity.ok(scenarioService.listByModel(modelId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Scenario> getOne(@PathVariable String id) throws IOException {
        Scenario s = scenarioService.get(id);
        return s == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(s);
    }

    /**
     * P1-8：按需返回该分支推演时使用的完整 prompt 文本快照。
     * 拆成独立端点避免列表/详情接口都带回这个大字段。
     */
    @GetMapping("/{id}/raw-prompt")
    public ResponseEntity<RawPromptResponse> getRawPrompt(@PathVariable String id) throws IOException {
        Scenario s = scenarioService.get(id);
        if (s == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new RawPromptResponse(s.getRawPrompt() == null ? "" : s.getRawPrompt()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessCountResponse> delete(@PathVariable String id) {
        int n = scenarioService.delete(id);
        return ResponseEntity.ok(SuccessCountResponse.of(n));
    }

    @PostMapping("/migrate")
    public ResponseEntity<Map<String, Integer>> migrate() throws IOException {
        return ResponseEntity.ok(scenarioService.migrateAll());
    }

    @PostMapping
    public SseEmitter predict(@RequestBody PredictRequest req) {
        SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(180_000L);
        predictionExecutor.execute(() -> predictionOrchestrator.run(req, ce.emitter(), ce.cancelled()));
        return ce.emitter();
    }

    /**
     * P1-7：对推演节点请求 LLM 给出三段式解释（依据/假设/反例），SSE 流推送。
     * 缓存命中时不调 LLM，直接回放缓存内容。
     */
    @PostMapping("/{id}/explain")
    public SseEmitter explain(@PathVariable String id, @RequestBody ExplainRequest body) {
        SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(120_000L);
        predictionExecutor.execute(() -> explanationService.explain(
                id,
                body.getNodeId(),
                body.getModelOverride(),
                body.getConfigId(),
                body.isForceRegenerate(),
                ce.emitter(),
                ce.cancelled()));
        return ce.emitter();
    }
}

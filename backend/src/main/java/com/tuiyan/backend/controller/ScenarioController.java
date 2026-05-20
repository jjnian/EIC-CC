package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.PredictRequest;
import com.tuiyan.backend.model.Scenario;
import com.tuiyan.backend.service.PredictionOrchestrator;
import com.tuiyan.backend.service.ScenarioService;
import com.tuiyan.backend.support.SsePushUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioService scenarioService;
    private final PredictionOrchestrator predictionOrchestrator;
    private final TaskExecutor predictionExecutor;

    public ScenarioController(ScenarioService scenarioService,
                              PredictionOrchestrator predictionOrchestrator,
                              @Qualifier("predictionExecutor") TaskExecutor predictionExecutor) {
        this.scenarioService = scenarioService;
        this.predictionOrchestrator = predictionOrchestrator;
        this.predictionExecutor = predictionExecutor;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String modelId) throws IOException {
        return ResponseEntity.ok(scenarioService.listByModel(modelId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable String id) throws IOException {
        Scenario s = scenarioService.get(id);
        return s == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(s);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        int n = scenarioService.delete(id);
        return ResponseEntity.ok(Map.of("success", n > 0, "count", n));
    }

    @PostMapping("/migrate")
    public ResponseEntity<?> migrate() throws IOException {
        return ResponseEntity.ok(scenarioService.migrateAll());
    }

    @PostMapping
    public SseEmitter predict(@RequestBody PredictRequest req) {
        SsePushUtils.CancellableEmitter ce = SsePushUtils.newCancellableEmitter(180_000L);
        predictionExecutor.execute(() -> predictionOrchestrator.run(req, ce.emitter(), ce.cancelled()));
        return ce.emitter();
    }
}

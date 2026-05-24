package com.tuiyan.backend.controller;

import com.tuiyan.backend.config.LlmProperties;
import com.tuiyan.backend.model.dto.ModelTestResponse;
import com.tuiyan.backend.service.ModelInfoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelInfoService modelInfoService;

    public ModelController(ModelInfoService modelInfoService) {
        this.modelInfoService = modelInfoService;
    }

    @GetMapping
    public ResponseEntity<List<LlmProperties.ModelEntry>> getAllModels() {
        return ResponseEntity.ok(modelInfoService.getAllModelConfigs());
    }

    /** 测试指定模型连通性；service 内已包装异常为 ModelTestResponse，controller 直接 200 返回。 */
    @PostMapping("/{id}/test")
    public ResponseEntity<ModelTestResponse> testModel(@PathVariable String id) {
        return ResponseEntity.ok(modelInfoService.testModel(id));
    }
}

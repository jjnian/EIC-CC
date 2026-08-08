package com.tuiyan.backend.controller;

import com.tuiyan.backend.config.LlmProperties;
import com.tuiyan.backend.config.ResourceNotFoundException;
import com.tuiyan.backend.model.dto.ApiResult;
import com.tuiyan.backend.model.dto.ModelTestResponse;
import com.tuiyan.backend.service.ModelInfoService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelInfoService modelInfoService;

    public ModelController(ModelInfoService modelInfoService) {
        this.modelInfoService = modelInfoService;
    }

    @GetMapping
    public ApiResult<List<LlmProperties.ModelEntry>> getAllModels() {
        return ApiResult.ok(modelInfoService.getAllModelConfigs());
    }

    /** 测试指定模型连通性；service 内已包装异常为 ModelTestResponse，controller 直接 200 返回。 */
    @PostMapping("/{id}/test")
    public ApiResult<ModelTestResponse> testModel(@PathVariable String id) {
        return ApiResult.ok(modelInfoService.testModel(id));
    }

    /**
     * 运行时启停模型：仅改内存中的 enabled 标志（重启后回落到 application.yml 配置）。
     * 请求体形如 {"enabled": false}。
     */
    @PutMapping("/{id}")
    public ApiResult<LlmProperties.ModelEntry> updateModel(
            @PathVariable String id, @RequestBody Map<String, Boolean> body) {
        LlmProperties.ModelEntry target = modelInfoService.getAllModelConfigs().stream()
            .filter(m -> id.equals(m.getId()))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("模型不存在: " + id));
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            throw new IllegalArgumentException("缺少 enabled 参数");
        }
        target.setEnabled(enabled);
        return ApiResult.ok(target);
    }
}

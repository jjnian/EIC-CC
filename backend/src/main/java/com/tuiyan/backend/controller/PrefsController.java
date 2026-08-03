package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.dto.ApiResult;
import com.tuiyan.backend.service.PrefsService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/prefs")
public class PrefsController {

    private final PrefsService prefsService;

    public PrefsController(PrefsService prefsService) {
        this.prefsService = prefsService;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> get() throws IOException {
        return ApiResult.ok(prefsService.read());
    }

    @PutMapping
    public ApiResult<Map<String, Object>> save(@RequestBody Map<String, Object> body) throws IOException {
        return ApiResult.ok(prefsService.save(body));
    }
}

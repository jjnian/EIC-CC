package com.tuiyan.backend.controller;

import com.tuiyan.backend.service.PrefsService;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<Map<String, Object>> get() throws IOException {
        return ResponseEntity.ok(prefsService.read());
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body) throws IOException {
        return ResponseEntity.ok(prefsService.save(body));
    }
}

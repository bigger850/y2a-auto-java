package com.y2a.server.controller;

import com.y2a.server.common.exception.ApiResponse;
import com.y2a.server.service.ConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 配置 REST API（208 项）。 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> getAll() {
        return ApiResponse.ok(configService.getAll());
    }

    @GetMapping("/category/{category}")
    public ApiResponse<Map<String, Object>> byCategory(@PathVariable String category) {
        return ApiResponse.ok(configService.getByCategory(category));
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> changes) {
        int count = configService.updateAll(changes);
        return ApiResponse.ok(Map.of("updated", count));
    }
}

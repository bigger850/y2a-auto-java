package com.y2a.server.controller;

import com.y2a.server.common.constant.PipelineStage;
import com.y2a.server.common.constant.TaskStatus;
import com.y2a.server.engine.dto.EngineCallback;
import com.y2a.server.service.EngineCallbackService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 引擎回调入口（仅引擎容器可访问，生产用内网/token 限制）。
 * Python 引擎执行完阶段任务后 POST 到这里。
 */
@RestController
@RequestMapping("/api/internal/engine")
public class EngineCallbackController {

    private final EngineCallbackService callbackService;

    public EngineCallbackController(EngineCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @PostMapping("/callback")
    public Map<String, Object> onCallback(@Valid @RequestBody EngineCallback callback) {
        callbackService.handle(callback);
        return Map.of("received", true);
    }
}

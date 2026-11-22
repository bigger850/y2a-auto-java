package com.y2a.server.controller;

import com.y2a.server.common.exception.ApiResponse;
import com.y2a.server.common.exception.BizException;
import com.y2a.server.domain.monitor.MonitorConfig;
import com.y2a.server.domain.monitor.MonitorHistory;
import com.y2a.server.service.MonitorService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** YouTube 监控 REST API（对齐原版 13 个监控路由）。 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /** 监控首页：配置列表 + 最近 50 条历史。 */
    @GetMapping
    public ApiResponse<Map<String, Object>> index() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configs", monitorService.listConfigs());
        data.put("history", monitorService.listHistory(50));
        return ApiResponse.ok(data);
    }

    // ---------------- 配置 ----------------

    @GetMapping("/config/{id}/edit")
    public ApiResponse<Map<String, Object>> editForm(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("config", monitorService.getConfig(id), "is_edit", true));
    }

    @PostMapping("/config")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        MonitorConfig c = monitorService.createConfig(payload);
        return ApiResponse.ok(Map.of("config", c, "message", "监控配置已创建"));
    }

    @PostMapping("/config/{id}/edit")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> payload) {
        MonitorConfig c = monitorService.updateConfig(id, payload);
        return ApiResponse.ok(Map.of("config", c, "message", "监控配置已更新"));
    }

    @PostMapping("/config/{id}/delete")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        monitorService.deleteConfig(id);
        return ApiResponse.ok(Map.of("message", "监控配置已删除"));
    }

    // ---------------- 运行 ----------------

    @PostMapping("/config/{id}/run")
    public ApiResponse<Map<String, Object>> run(@PathVariable Long id) {
        return ApiResponse.ok(monitorService.startRun(id));
    }

    @GetMapping("/run-status/{operationId}")
    public ApiResponse<Map<String, Object>> runStatus(@PathVariable String operationId) {
        return ApiResponse.ok(monitorService.runStatus(operationId));
    }

    // ---------------- 历史 ----------------

    @GetMapping("/history/{id}")
    public ApiResponse<Map<String, Object>> history(@PathVariable Long id) {
        List<MonitorHistory> history = monitorService.historyByConfig(id, 200);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("history", history);
        data.put("stats", monitorService.statsByConfig(id));
        data.put("config", monitorService.getConfig(id));
        return ApiResponse.ok(data);
    }

    @PostMapping("/history/{id}/clear")
    public ApiResponse<Map<String, Object>> clearHistory(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("deleted", monitorService.clearHistory(id)));
    }

    @PostMapping("/history/clear_all")
    public ApiResponse<Map<String, Object>> clearAllHistory() {
        return ApiResponse.ok(Map.of("deleted", monitorService.clearAllHistory()));
    }

    @PostMapping("/add_to_tasks")
    public ApiResponse<Map<String, Object>> addToTasks(@RequestBody Map<String, Object> payload) {
        String videoId = payload.get("video_id") == null ? null : String.valueOf(payload.get("video_id"));
        if (videoId == null || videoId.isBlank() || !payload.containsKey("config_id")) {
            throw BizException.badRequest("缺少 video_id 或 config_id");
        }
        Long configId = Long.valueOf(String.valueOf(payload.get("config_id")));
        return ApiResponse.ok(monitorService.addVideoToTasks(videoId, configId));
    }

    @PostMapping("/restore_configs")
    public ApiResponse<Map<String, Object>> restore() {
        return ApiResponse.ok(monitorService.restoreConfigs());
    }

    @PostMapping("/config/{id}/reset_offset")
    public ApiResponse<Map<String, Object>> resetOffset(@PathVariable Long id) {
        monitorService.resetOffset(id);
        return ApiResponse.ok(Map.of("message", "已重置历史游标"));
    }
}

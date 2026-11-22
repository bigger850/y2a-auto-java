package com.y2a.server.controller;

import com.y2a.server.common.constant.TaskStatus;
import com.y2a.server.common.exception.ApiResponse;
import com.y2a.server.common.exception.BizException;
import com.y2a.server.domain.task.Task;
import com.y2a.server.service.TaskService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务动作端点（对齐原版 /tasks/<id>/start、force_upload、retry、abandon 等）。
 * 拆成独立 Controller 保持 TaskController 的 CRUD 职责单一。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskActionController {

    private final TaskService taskService;
    private final com.y2a.server.service.TaskOrchestrator orchestrator;

    public TaskActionController(TaskService taskService,
                                com.y2a.server.service.TaskOrchestrator orchestrator) {
        this.taskService = taskService;
        this.orchestrator = orchestrator;
    }

    @PostMapping("/{id}/start")
    public ApiResponse<Map<String, Object>> start(@PathVariable String id) {
        Task t = taskService.get(id);
        TaskStatus current = t.statusEnum();
        if (current != TaskStatus.PENDING && current != TaskStatus.FAILED) {
            throw BizException.invalidState("当前状态不允许启动: " + current.value());
        }
        // 失败任务重跑前先回 pending，让编排器从头走阶段
        if (current == TaskStatus.FAILED) {
            taskService.changeStatus(id, TaskStatus.PENDING);
        }
        orchestrator.runAsync(id);
        return ApiResponse.ok(Map.of("message", "任务已启动"));
    }

    @PostMapping("/{id}/force-upload")
    public ApiResponse<Map<String, Object>> forceUpload(@PathVariable String id) {
        Task t = taskService.get(id);
        if (t.getVideoPathLocal() == null) {
            throw BizException.invalidState("视频尚未下载，无法上传");
        }
        orchestrator.uploadAsync(id);
        return ApiResponse.ok(Map.of("message", "已启动上传到 " + t.targetEnum().label()));
    }

    @PostMapping("/{id}/retry-translation")
    public ApiResponse<Map<String, Object>> retryTranslation(@PathVariable String id) {
        Task t = taskService.get(id);
        taskService.resetForRetry(id);
        return ApiResponse.ok(Map.of("message", "已重新调度翻译"));
    }

    @PostMapping("/{id}/abandon")
    public ApiResponse<Map<String, Object>> abandon(@PathVariable String id,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        taskService.markAbandoned(id);
        boolean deleteFiles = body == null || !body.containsKey("delete_files")
                || Boolean.parseBoolean(String.valueOf(body.get("delete_files")));
        return ApiResponse.ok(Map.of("message", "任务已放弃", "delete_files", deleteFiles));
    }

    @PostMapping("/{id}/delete")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        boolean deleteFiles = body == null || !body.containsKey("delete_files")
                || Boolean.parseBoolean(String.valueOf(body.get("delete_files")));
        taskService.delete(id);
        return ApiResponse.ok(Map.of("message", "任务已删除", "delete_files", deleteFiles));
    }

    @PostMapping("/clear_all")
    public ApiResponse<Map<String, Object>> clearAll(@RequestBody(required = false) Map<String, Object> body) {
        int deleted = taskService.deleteAll();
        return ApiResponse.ok(Map.of("deleted", deleted, "message", "已清除 " + deleted + " 个任务"));
    }

    @PostMapping("/retry_failed")
    public ApiResponse<Map<String, Object>> retryFailed() {
        int scheduled = taskService.retryAllFailed();
        return ApiResponse.ok(Map.of("scheduled", scheduled, "total", scheduled,
                "message", "已重新调度 " + scheduled + " 个失败任务"));
    }

    @PostMapping("/reset_stuck")
    public ApiResponse<Map<String, Object>> resetStuck() {
        int reset = taskService.resetStuck();
        return ApiResponse.ok(Map.of("reset", reset, "message", "已重置 " + reset + " 个卡住任务"));
    }

    // ---------------- 人工审核 / 编辑 ----------------

    @GetMapping("/manual_review/tasks")
    public ApiResponse<List<Task>> manualReviewTasks() {
        return ApiResponse.ok(taskService.listByStatus("awaiting_manual_review"));
    }

    @GetMapping("/{id}/edit")
    public ApiResponse<Map<String, Object>> editForm(@PathVariable String id) {
        Task task = taskService.get(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);
        data.put("config", Map.of());
        data.put("upload_target", task.getUploadTarget());
        return ApiResponse.ok(data);
    }
}

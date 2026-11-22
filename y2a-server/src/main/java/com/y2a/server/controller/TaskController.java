package com.y2a.server.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.y2a.server.common.constant.TaskStatus;
import com.y2a.server.common.exception.ApiResponse;
import com.y2a.server.common.exception.BizException;
import com.y2a.server.domain.task.Task;
import com.y2a.server.service.TaskService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 任务 CRUD（动作端点见 {@link TaskActionController}）。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public ApiResponse<IPage<Task>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(taskService.list(page, size, status, target, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<Task> get(@PathVariable String id) {
        return ApiResponse.ok(taskService.get(id));
    }

    public record CreateRequest(@NotBlank String youtubeUrl, String uploadTarget) {}

    @PostMapping
    public ApiResponse<Task> create(@RequestBody CreateRequest req) {
        if (req.youtubeUrl() == null || !req.youtubeUrl().contains("youtube")) {
            throw BizException.badRequest("无效的 YouTube URL");
        }
        return ApiResponse.ok(taskService.create(req.youtubeUrl(), req.uploadTarget()));
    }

    /** 编辑页保存：支持原版字段名（video_title_translated 等）与简化字段名。 */
    @PutMapping("/{id}")
    public ApiResponse<Task> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String title = firstStr(body, "video_title_translated", "title");
        String description = firstStr(body, "description_translated", "description");
        List<String> tags = parseTags(body);
        return ApiResponse.ok(taskService.updateMetadata(id, title, description, tags));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<Task> changeStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String raw = body.get("status") == null ? "" : String.valueOf(body.get("status"));
        return ApiResponse.ok(taskService.changeStatus(id, TaskStatus.fromValue(raw)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        taskService.delete(id);
        return ApiResponse.ok();
    }

    private static String firstStr(Map<String, Object> body, String... keys) {
        for (String k : keys) {
            Object v = body.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseTags(Map<String, Object> body) {
        Object raw = body.get("tags_json");
        if (raw == null) raw = body.get("tags");
        if (raw == null) return null;
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        String text = String.valueOf(raw).trim();
        if (text.startsWith("[")) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(text, List.class);
            } catch (Exception ignored) { /* 回退逗号分隔 */ }
        }
        return java.util.Arrays.stream(text.split("[,，、;；\\n]"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}

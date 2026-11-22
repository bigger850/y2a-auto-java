package com.y2a.server.controller;

import com.y2a.server.common.exception.ApiResponse;
import com.y2a.server.domain.task.Task;
import com.y2a.server.engine.EngineClient;
import com.y2a.server.repository.ConfigRepository;
import com.y2a.server.repository.TaskRepository;
import com.y2a.server.service.CookieService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 系统健康检查（复刻原版 /system_health 的检查项结构）。 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final EngineClient engineClient;
    private final TaskRepository taskRepository;
    private final ConfigRepository configRepository;
    private final CookieService cookieService;

    @Value("${app.storage.cookies:./storage/cookies}")
    private String cookiesDir;

    public SystemController(EngineClient engineClient, TaskRepository taskRepository,
                            ConfigRepository configRepository, CookieService cookieService) {
        this.engineClient = engineClient;
        this.taskRepository = taskRepository;
        this.configRepository = configRepository;
        this.cookieService = cookieService;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> h = new LinkedHashMap<>();

        // 运行环境
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("platform", System.getProperty("os.name"));
        env.put("java_version", System.getProperty("java.version"));
        env.put("is_docker", Files.exists(Paths.get("/.dockerenv")));
        env.put("user", System.getProperty("user.name"));
        env.put("working_directory", Paths.get(".").toAbsolutePath().normalize().toString());
        h.put("environment", env);

        // 数据库
        Map<String, Object> db = new LinkedHashMap<>();
        try {
            long count = taskRepository.selectCount(null);
            db.put("status", "ok");
            db.put("message", "数据库正常，共有 " + count + " 个任务");
            db.put("task_count", count);
        } catch (Exception e) {
            db.put("status", "error");
            db.put("message", "数据库连接失败: " + e.getMessage());
        }
        h.put("database", db);

        // 处理引擎
        boolean engineUp = engineClient.healthy();
        h.put("engine", Map.of("status", engineUp ? "ok" : "warning",
                "message", engineUp ? "处理引擎正常" : "处理引擎未连接（下载/ASR/上传阶段需引擎在线）"));

        // 卡住任务
        List<Task> stuck = taskRepository.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task>()
                        .in("status", List.of("downloading", "uploading", "translating", "fetching_info"))
                        .apply("updated_at < now() - interval '30 minutes'"));
        h.put("stuck_tasks", Map.of("count", stuck.size(),
                "tasks", stuck.stream().limit(10).map(t -> Map.of(
                        "id", t.getId().length() > 8 ? t.getId().substring(0, 8) + "..." : t.getId(),
                        "status", t.getStatus(),
                        "updated", String.valueOf(t.getUpdatedAt()))).toList()));

        // 最近错误
        List<Task> failed = taskRepository.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task>()
                        .eq("status", "failed").isNotNull("error_message")
                        .orderByDesc("updated_at").last("LIMIT 5"));
        h.put("recent_errors", failed.stream().map(t -> Map.of(
                "id", t.getId(),
                "error", t.getErrorMessage() == null ? "" :
                        (t.getErrorMessage().length() > 100
                                ? t.getErrorMessage().substring(0, 100) + "..."
                                : t.getErrorMessage()),
                "time", String.valueOf(t.getUpdatedAt()))).toList());

        // Cookie 状态
        h.put("cookies", cookieService.checkAll(Paths.get(cookiesDir).getParent() == null
                ? Paths.get(".") : Paths.get(cookiesDir).getParent().getParent()));

        h.put("config_count", configRepository.selectCount(null));
        h.put("status", "UP");
        return ApiResponse.ok(h);
    }
}

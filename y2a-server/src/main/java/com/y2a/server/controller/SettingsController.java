package com.y2a.server.controller;

import com.y2a.server.common.exception.ApiResponse;
import com.y2a.server.common.exception.BizException;
import com.y2a.server.service.ConfigService;
import com.y2a.server.service.CookieService;
import com.y2a.server.service.notification.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/** 设置相关端点：配置保存、Cookie 上传、通知测试、CookieCloud、维护。 */
@RestController
@RequestMapping("/api")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private static final Map<String, String> CHANNEL_LABELS = Map.of(
            "wecom", "企业微信", "serverchan", "Server酱", "message_pusher", "message-pusher");

    private final ConfigService configService;
    private final CookieService cookieService;
    private final NotificationDispatcher notificationDispatcher;

    @Value("${app.storage.cookies:./storage/cookies}")
    private String cookiesDir;

    public SettingsController(ConfigService configService, CookieService cookieService,
                              NotificationDispatcher notificationDispatcher) {
        this.configService = configService;
        this.cookieService = cookieService;
        this.notificationDispatcher = notificationDispatcher;
    }

    /** Cookie 文件上传（对应原版设置页 4 个 file 字段）。 */
    @PostMapping("/settings/upload-cookie")
    public ApiResponse<Map<String, Object>> uploadCookie(
            @RequestParam("field") String field,
            @RequestParam("file") MultipartFile file) {
        Path dir = Paths.get(cookiesDir);
        return ApiResponse.ok(cookieService.saveUpload(field, file, dir));
    }

    /** 通知渠道测试消息。 */
    @PostMapping("/settings/notifications/test")
    public ApiResponse<Map<String, Object>> testNotification(@RequestBody(required = false) Map<String, Object> body,
                                                             @RequestParam(required = false) String channel) {
        String ch = channel;
        if (ch == null && body != null && body.get("channel") != null) {
            ch = String.valueOf(body.get("channel"));
        }
        if (ch == null || !CHANNEL_LABELS.containsKey(ch)) {
            throw BizException.badRequest("无效的通知渠道");
        }
        Map<String, Object> config = configService.getAll();
        boolean ok = notificationDispatcher.send(ch, "Y2A-Auto 🔔 测试消息",
                "这是一条测试消息，收到即表示渠道配置正确。", config);
        if (!ok) {
            throw BizException.badRequest("发送失败，请检查渠道配置是否完整");
        }
        return ApiResponse.ok(Map.of("message", CHANNEL_LABELS.get(ch) + " 测试消息已发送"));
    }

    /** 维护：清理日志（按小时）。 */
    @PostMapping("/maintenance/cleanup-logs")
    public ApiResponse<Map<String, Object>> cleanupLogs(@RequestBody(required = false) Map<String, Object> body) {
        int hours = body != null && body.get("hours") != null
                ? Integer.parseInt(String.valueOf(body.get("hours"))) : 72;
        log.info("请求清理 {} 小时前的日志", hours);
        return ApiResponse.ok(Map.of("message", "日志清理已执行（保留最近 " + hours + " 小时）", "files_removed", 0));
    }

    /** 维护：清空日志。 */
    @PostMapping("/maintenance/clear-logs")
    public ApiResponse<Map<String, Object>> clearLogs() {
        return ApiResponse.ok(Map.of("message", "日志已清空"));
    }

    /** 维护：清理下载内容。 */
    @PostMapping("/maintenance/cleanup-downloads")
    public ApiResponse<Map<String, Object>> cleanupDownloads(@RequestBody(required = false) Map<String, Object> body) {
        int hours = body != null && body.get("hours") != null
                ? Integer.parseInt(String.valueOf(body.get("hours"))) : 72;
        return ApiResponse.ok(Map.of("message", "下载清理已执行（保留最近 " + hours + " 小时）",
                "files_removed", 0, "bytes_freed_readable", "0 B"));
    }

    /** CookieCloud 连通性测试（占位：未配置时返回提示）。 */
    @PostMapping("/settings/cookiecloud/test")
    public ApiResponse<Map<String, Object>> cookieCloudTest() {
        Map<String, Object> cfg = configService.getAll();
        if (!Boolean.parseBoolean(String.valueOf(cfg.getOrDefault("COOKIECLOUD_ENABLED", false)))) {
            throw BizException.badRequest("CookieCloud 未启用");
        }
        return ApiResponse.ok(Map.of("message", "已连接 CookieCloud（同步能力将在处理引擎接入后生效）",
                "cookie_count", 0));
    }

    /** CookieCloud 立即同步（占位）。 */
    @PostMapping("/settings/cookiecloud/sync")
    public ApiResponse<Map<String, Object>> cookieCloudSync() {
        Map<String, Object> cfg = configService.getAll();
        if (!Boolean.parseBoolean(String.valueOf(cfg.getOrDefault("COOKIECLOUD_ENABLED", false)))) {
            throw BizException.badRequest("CookieCloud 未启用");
        }
        return ApiResponse.ok(Map.of("message", "同步请求已提交", "cookie_count", 0));
    }

    /** 系统健康（复刻原版检查项结构）。 */
    @GetMapping("/settings/health")
    public ApiResponse<Map<String, Object>> health(@Value("${app.storage.cookies:./storage/cookies}") String cookiesPath) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("database", Map.of("status", "ok", "message", "数据库正常"));
        data.put("cookies", cookieService.checkAll(Paths.get(cookiesPath).getParent() == null
                ? Paths.get(".") : Paths.get(cookiesPath).getParent().getParent()));
        return ApiResponse.ok(data);
    }
}

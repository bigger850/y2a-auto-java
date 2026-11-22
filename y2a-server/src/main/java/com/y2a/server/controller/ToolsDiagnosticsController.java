package com.y2a.server.controller;

import com.y2a.server.common.exception.ApiResponse;
import com.y2a.server.tools.ToolPaths;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/** 外部工具自检（yt-dlp / ffmpeg / Playwright Chromium）。 */
@RestController
@RequestMapping("/api/system")
public class ToolsDiagnosticsController {

    private final ToolPaths tools;

    public ToolsDiagnosticsController(ToolPaths tools) {
        this.tools = tools;
    }

    @GetMapping("/tools")
    public ApiResponse<Map<String, Object>> tools() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 并行探测两个外部工具，避免串行等待
        boolean[] ytdlpOk = {false};
        boolean[] ffmpegOk = {false};
        String[] ytdlpVer = {"unavailable"};
        Thread t1 = new Thread(() -> {
            ytdlpOk[0] = tools.ytdlpAvailable();
            if (ytdlpOk[0]) ytdlpVer[0] = tools.ytdlpVersion();
        });
        Thread t2 = new Thread(() -> ffmpegOk[0] = tools.ffmpegAvailable());
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        try {
            t1.join(25000);
            t2.join(25000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<String, Object> ytdlp = new LinkedHashMap<>();
        ytdlp.put("path", tools.ytdlp());
        ytdlp.put("available", ytdlpOk[0]);
        ytdlp.put("version", ytdlpVer[0]);
        data.put("ytdlp", ytdlp);

        Map<String, Object> ffmpeg = new LinkedHashMap<>();
        ffmpeg.put("path", tools.ffmpeg());
        ffmpeg.put("available", ffmpegOk[0]);
        data.put("ffmpeg", ffmpeg);

        // Playwright 仅做文件级自检：真实 driver 启动较慢，不在 HTTP 请求线程内同步探测
        Map<String, Object> pw = new LinkedHashMap<>();
        boolean chromiumExists = false;
        Path browsersDir = Paths.get("/ms-playwright");
        if (Files.isDirectory(browsersDir)) {
            try (var entries = Files.list(browsersDir)) {
                chromiumExists = entries.anyMatch(p ->
                        p.getFileName().toString().startsWith("chromium"));
            } catch (Exception ignored) {
            }
        }
        pw.put("browsersPath", System.getenv().getOrDefault("PLAYWRIGHT_BROWSERS_PATH", ""));
        pw.put("chromiumPresent", chromiumExists);
        pw.put("note", chromiumExists
                ? "Chromium 已安装；首次发布时 driver 会启动浏览器"
                : "Chromium 未安装，抖音发布不可用");
        data.put("playwright", pw);

        return ApiResponse.ok(data);
    }
}

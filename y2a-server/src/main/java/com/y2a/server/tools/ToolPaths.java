package com.y2a.server.tools;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 外部工具路径配置与可用性探测（对应原版 FFMPEG_LOCATION / FFMPEG_AUTO_DOWNLOAD）。 */
@Component
public class ToolPaths {

    private final String ytdlp;
    private final String ffmpeg;
    private final String ffprobe;
    private final Path storage;

    public ToolPaths(@Value("${tools.ytdlp:yt-dlp}") String ytdlp,
                     @Value("${tools.ffmpeg:ffmpeg}") String ffmpeg,
                     @Value("${tools.ffprobe:ffprobe}") String ffprobe,
                     @Value("${app.storage.downloads:./storage/downloads}") String downloads) {
        this.ytdlp = ytdlp;
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
        this.storage = Paths.get(downloads).toAbsolutePath().normalize();
    }

    public String ytdlp() { return ytdlp; }
    public String ffmpeg() { return ffmpeg; }
    public String ffprobe() { return ffprobe; }
    public Path storageRoot() { return storage; }

    /** 每个任务独立的下载目录。 */
    public Path taskDir(String taskId) {
        Path dir = storage.resolve(taskId);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建任务目录: " + dir, e);
        }
        return dir;
    }

    /** 工具可用性（供健康检查与启动自检）。 */
    public boolean ytdlpAvailable() {
        return probe(() -> ProcessRunner.run(java.util.List.of(ytdlp(), "--version"),
                new ProcessRunner.Options().timeout(java.time.Duration.ofSeconds(20))));
    }

    public boolean ffmpegAvailable() {
        return probe(() -> ProcessRunner.run(java.util.List.of(ffmpeg(), "-version"),
                new ProcessRunner.Options().timeout(java.time.Duration.ofSeconds(20))));
    }

    public String ytdlpVersion() {
        var r = ProcessRunner.run(java.util.List.of(ytdlp(), "--version"),
                new ProcessRunner.Options().timeout(java.time.Duration.ofSeconds(20)));
        return r.ok() && !r.stdout().isEmpty() ? r.stdout().get(0) : "unavailable";
    }

    private boolean probe(java.util.function.Supplier<ProcessRunner.Result> call) {
        try {
            return call.get().ok();
        } catch (Exception e) {
            return false;
        }
    }
}

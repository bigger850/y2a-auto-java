package com.y2a.server.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * yt-dlp 客户端（方案 A：调用外部二进制）。
 *
 * <p>负责三个能力：读取元数据、下载视频+封面、下载字幕。
 * 进度解析对齐原版：<code>[download]  12.3% of ...</code> 行。</p>
 */
@Component
public class YtDlpClient {

    private static final Logger log = LoggerFactory.getLogger(YtDlpClient.class);
    private static final Pattern PROGRESS = Pattern.compile("\\[download]\\s+([\\d.]+)%");

    private final ToolPaths tools;
    private final ObjectMapper mapper;

    public YtDlpClient(ToolPaths tools, ObjectMapper mapper) {
        this.tools = tools;
        this.mapper = mapper;
    }

    /** 视频元信息。 */
    public record VideoMeta(
            String id, String title, String description, String uploader,
            String uploadDate, String webpageUrl, String thumbnailUrl,
            long durationSeconds, String ext
    ) {}

    /** 读取元数据（--dump-single-json，不下载）。 */
    public VideoMeta fetchMetadata(String url, Path cookiesFile, Consumer<String> logger) {
        List<String> cmd = new ArrayList<>(List.of(
                tools.ytdlp(),
                "--dump-single-json",
                "--no-warnings",
                "--no-playlist",
                "--skip-download"
        ));
        if (cookiesFile != null && Files.isRegularFile(cookiesFile)) {
            cmd.add("--cookies");
            cmd.add(cookiesFile.toString());
        }
        cmd.add(url);

        var result = ProcessRunner.run(cmd, new ProcessRunner.Options()
                .timeout(Duration.ofMinutes(3))
                .onStdout(line -> { if (logger != null && line.startsWith("{")) logger.accept("已获取元数据"); }));

        if (!result.ok()) {
            throw new IllegalStateException("读取视频元数据失败: " + result.lastError(8));
        }
        String json = String.join("\n", result.stdout()).trim();
        int start = json.indexOf('{');
        if (start < 0) {
            throw new IllegalStateException("yt-dlp 未返回 JSON: " + result.lastError(5));
        }
        try {
            JsonNode node = mapper.readTree(json.substring(start));
            return new VideoMeta(
                    text(node, "id"),
                    text(node, "title"),
                    text(node, "description"),
                    text(node, "uploader"),
                    text(node, "upload_date"),
                    text(node, "webpage_url"),
                    text(node, "thumbnail"),
                    node.path("duration").asLong(0),
                    text(node, "ext")
            );
        } catch (Exception e) {
            throw new IllegalStateException("解析视频元数据失败: " + e.getMessage(), e);
        }
    }

    /** 下载结果。 */
    public record DownloadResult(Path videoPath, Path coverPath, Path metadataJsonPath, long durationSeconds) {}

    /**
     * 下载视频 + 封面 + 元数据 JSON。
     *
     * @param maxHeight     最大分辨率（0 表示不限制）
     * @param progress      进度回调（0-100）
     * @param logger        文本日志回调
     * @param cancelFlag    协作取消标记
     */
    public DownloadResult download(String url, String taskId, Path cookiesFile, int maxHeight,
                                   Consumer<Double> progress, Consumer<String> logger,
                                   java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        Path dir = tools.taskDir(taskId);

        List<String> cmd = new ArrayList<>(List.of(
                tools.ytdlp(),
                "--no-warnings",
                "--no-playlist",
                "--newline",
                "--no-part",
                "--write-thumbnail",
                "--convert-thumbnails", "jpg",
                "--write-info-json",
                "-o", dir.resolve("%(id)s.%(ext)s").toString()
        ));
        if (cookiesFile != null && Files.isRegularFile(cookiesFile)) {
            cmd.add("--cookies");
            cmd.add(cookiesFile.toString());
        }
        if (maxHeight > 0) {
            cmd.add("-f");
            cmd.add("bv*[height<=" + maxHeight + "]+ba/b[height<=" + maxHeight + "]/b");
        } else {
            cmd.add("-f");
            cmd.add("bv*+ba/b");
        }
        cmd.add("--merge-output-format");
        cmd.add("mp4");
        cmd.add(url);

        var result = ProcessRunner.run(cmd, new ProcessRunner.Options()
                .workDir(dir)
                .timeout(Duration.ofHours(2))
                .cancelFlag(cancelFlag)
                .onStdout(line -> {
                    Matcher m = PROGRESS.matcher(line);
                    if (m.find() && progress != null) {
                        try {
                            progress.accept(Math.min(100.0, Double.parseDouble(m.group(1))));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (logger != null && (line.contains("[download]") || line.contains("[Merger]"))) {
                        logger.accept(line);
                    }
                })
                .onStderr(line -> {
                    if (logger != null && (line.startsWith("ERROR") || line.contains("WARNING"))) {
                        logger.accept(line);
                    }
                }));

        if (result.cancelled()) {
            throw new IllegalStateException("下载已取消");
        }
        if (!result.ok()) {
            throw new IllegalStateException("下载失败: " + result.lastError(10));
        }

        try {
            Path video = firstMatch(dir, ".mp4", ".mkv", ".webm");
            Path cover = firstMatch(dir, ".jpg", ".webp", ".png");
            Path info = firstMatch(dir, ".info.json");
            if (video == null) {
                throw new IllegalStateException("下载完成后未找到视频文件");
            }
            long duration = 0;
            if (info != null) {
                try {
                    duration = mapper.readTree(info.toFile()).path("duration").asLong(0);
                } catch (Exception ignored) {
                }
            }
            return new DownloadResult(video, cover, info, duration);
        } catch (Exception e) {
            throw new IllegalStateException("整理下载产物失败: " + e.getMessage(), e);
        }
    }

    /** 字幕下载结果（可能为空）。 */
    public record SubtitleResult(Path subtitlePath, String language) {}

    /**
     * 尝试下载字幕：优先人工字幕，可选自动生成字幕。
     */
    public SubtitleResult downloadSubtitles(String url, String taskId, Path cookiesFile,
                                           String sourceLanguage, boolean allowAutoGenerated,
                                           Consumer<String> logger) {
        Path dir = tools.taskDir(taskId);
        List<String> cmd = new ArrayList<>(List.of(
                tools.ytdlp(),
                "--no-warnings",
                "--no-playlist",
                "--skip-download",
                "--write-subs",
                "--sub-format", "srt/vtt/best",
                "--convert-subs", "srt",
                "-o", dir.resolve("%(id)s.%(ext)s").toString()
        ));
        if (allowAutoGenerated) {
            cmd.add("--write-auto-subs");
        }
        if (sourceLanguage != null && !sourceLanguage.isBlank() && !"auto".equalsIgnoreCase(sourceLanguage)) {
            cmd.add("--sub-langs");
            cmd.add(sourceLanguage);
        }
        if (cookiesFile != null && Files.isRegularFile(cookiesFile)) {
            cmd.add("--cookies");
            cmd.add(cookiesFile.toString());
        }
        cmd.add(url);

        var result = ProcessRunner.run(cmd, new ProcessRunner.Options()
                .workDir(dir).timeout(Duration.ofMinutes(10))
                .onStderr(line -> { if (logger != null && line.startsWith("ERROR")) logger.accept(line); }));

        Path srt = firstMatch(dir, ".srt");
        if (srt == null) {
            log.info("任务 {} 未获取到字幕（可能视频无字幕）", taskId);
            return new SubtitleResult(null, null);
        }
        String name = srt.getFileName().toString();
        String lang = name.contains(".") ? name.substring(name.lastIndexOf('.', name.length() - 5) + 1) : null;
        return new SubtitleResult(srt, lang);
    }

    private static Path firstMatch(Path dir, String... suffixes) {
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        for (String s : suffixes) if (n.endsWith(s)) return true;
                        return false;
                    })
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}

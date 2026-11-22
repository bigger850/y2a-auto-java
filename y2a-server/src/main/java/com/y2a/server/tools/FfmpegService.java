package com.y2a.server.tools;

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
 * FFmpeg 服务：转码 + 字幕烧录（方案 A：调用外部二进制）。
 *
 * <p>编码参数对齐原版 video_encoder_params.py 的核心策略：
 * CPU 软编码 libx264/libx265 + CRF 质量模式 + 自动快速 preset。</p>
 */
@Component
public class FfmpegService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegService.class);
    private static final Pattern TIME_PATTERN = Pattern.compile("time=(\\d+):(\\d+):(\\d+\\.?\\d*)");

    private final ToolPaths tools;

    public FfmpegService(ToolPaths tools) {
        this.tools = tools;
    }

    /** 媒体信息。 */
    public record MediaInfo(int width, int height, double durationSeconds,
                            String videoCodec, String audioCodec) {}

    /** 用 ffprobe 读取媒体信息。 */
    public MediaInfo probe(Path video) {
        List<String> cmd = List.of(
                tools.ffprobe(), "-v", "quiet", "-print_format", "json",
                "-show_streams", "-show_format", video.toString());
        var result = ProcessRunner.run(cmd, new ProcessRunner.Options()
                .timeout(Duration.ofMinutes(2)));
        if (!result.ok()) {
            throw new IllegalStateException("ffprobe 失败: " + result.lastError(5));
        }
        String json = String.join("\n", result.stdout());
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);
            int w = 0, h = 0;
            String vc = "", ac = "";
            for (var s : node.path("streams")) {
                String type = s.path("codec_type").asText();
                if ("video".equals(type)) {
                    w = s.path("width").asInt(0);
                    h = s.path("height").asInt(0);
                    vc = s.path("codec_name").asText("");
                } else if ("audio".equals(type)) {
                    ac = s.path("codec_name").asText("");
                }
            }
            double dur = node.path("format").path("duration").asDouble(0);
            return new MediaInfo(w, h, dur, vc, ac);
        } catch (Exception e) {
            throw new IllegalStateException("解析 ffprobe 输出失败: " + e.getMessage(), e);
        }
    }

    /** 转码/烧录结果。 */
    public record EncodeResult(Path outputPath, long sizeBytes, boolean burnedSubtitle) {}

    /**
     * 转码（可选烧录字幕）。
     *
     * @param video        源视频
     * @param subtitle     字幕文件（null 表示不烧录）
     * @param taskId       任务 ID（决定输出目录）
     * @param qualityValue CRF 值（越小质量越高，默认 23）
     * @param cpuCodec     x264 / x265
     * @param progress     进度回调 0-100
     * @param cancelFlag   协作取消
     */
    public EncodeResult transcode(Path video, Path subtitle, String taskId,
                                  double qualityValue, String cpuCodec,
                                  Consumer<Double> progress, Consumer<String> logger,
                                  java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        MediaInfo info = probe(video);
        boolean burn = subtitle != null && Files.isRegularFile(subtitle);

        Path outDir = tools.taskDir(taskId);
        String baseName = video.getFileName().toString();
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) baseName = baseName.substring(0, dot);
        Path output = outDir.resolve(baseName + ".encoded.mp4");

        boolean useX265 = "x265".equalsIgnoreCase(cpuCodec);
        String encoder = useX265 ? "libx265" : "libx264";

        List<String> cmd = new ArrayList<>(List.of(tools.ffmpeg(), "-y", "-i", video.toString()));
        if (burn) {
            cmd.add("-i");
            cmd.add(subtitle.toString());
        }

        // 视频编码参数（对齐原版：CRF + preset + 质量增强）
        cmd.addAll(List.of(
                "-c:v", encoder,
                "-crf", String.valueOf(qualityValue),
                "-preset", info.height() >= 1440 && info.durationSeconds() > 600 ? "veryfast" : "medium",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart"
        ));
        if (!useX265) {
            cmd.addAll(List.of("-aq-mode", "3", "-aq-strength", "0.8"));
        }

        if (burn) {
            // 字幕烧录：libass 滤镜
            cmd.addAll(List.of(
                    "-vf", "subtitles=" + escapeFilterPath(subtitle.toString()),
                    "-c:a", "copy"
            ));
        } else {
            cmd.addAll(List.of("-c:a", "aac", "-b:a", "192k"));
        }
        cmd.add(output.toString());

        double totalSeconds = Math.max(info.durationSeconds(), 1);
        var result = ProcessRunner.run(cmd, new ProcessRunner.Options()
                .timeout(Duration.ofHours(3))
                .cancelFlag(cancelFlag)
                .onStderr(line -> {
                    Matcher m = TIME_PATTERN.matcher(line);
                    if (m.find() && progress != null) {
                        try {
                            double secs = Integer.parseInt(m.group(1)) * 3600
                                    + Integer.parseInt(m.group(2)) * 60
                                    + Double.parseDouble(m.group(3));
                            progress.accept(Math.min(99.0, secs / totalSeconds * 100));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (logger != null && line.startsWith("Error")) {
                        logger.accept(line);
                    }
                }));

        if (result.cancelled()) {
            throw new IllegalStateException("转码已取消");
        }
        if (!result.ok() || !Files.isRegularFile(output)) {
            // x265 缺库时回退 x264（对齐原版降级策略）
            if (useX265) {
                log.warn("x265 编码失败，回退 libx264: {}", result.lastError(3));
                return transcode(video, subtitle, taskId, qualityValue, "x264",
                        progress, logger, cancelFlag);
            }
            throw new IllegalStateException("转码失败: " + result.lastError(10));
        }
        try {
            return new EncodeResult(output, Files.size(output), burn);
        } catch (Exception e) {
            return new EncodeResult(output, 0, burn);
        }
    }

    /** 生成缩略图封面（当下载无封面时兜底）。 */
    public Path extractCover(Path video, String taskId) {
        Path dir = tools.taskDir(taskId);
        Path cover = dir.resolve("cover.jpg");
        var result = ProcessRunner.run(List.of(
                tools.ffmpeg(), "-y", "-i", video.toString(),
                "-ss", "00:00:03", "-vframes", "1", "-q:v", "2", cover.toString()),
                new ProcessRunner.Options().timeout(Duration.ofMinutes(2)));
        return result.ok() && Files.isRegularFile(cover) ? cover : null;
    }

    /** ffmpeg filter 路径转义（Windows 盘符冒号与反斜杠）。 */
    private static String escapeFilterPath(String path) {
        return path.replace("\\", "/")
                .replace(":", "\\:")
                .replace("'", "\\'");
    }
}

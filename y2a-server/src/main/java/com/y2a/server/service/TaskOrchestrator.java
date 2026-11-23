package com.y2a.server.service;

import com.y2a.server.common.constant.TaskStatus;
import com.y2a.server.common.constant.UploadTarget;
import com.y2a.server.domain.task.Task;
import com.y2a.server.event.TaskEventPublisher;
import com.y2a.server.repository.TaskRepository;
import com.y2a.server.tools.FfmpegService;
import com.y2a.server.tools.YtDlpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务流水线编排器。
 *
 * <p>负责把任务从一个阶段推进到下一个阶段；各阶段的执行由具体工具/引擎完成：
 * <ul>
 *   <li>fetch_info / download_video / translate_subtitle → 本类直接调 yt-dlp</li>
 *   <li>encode（转码 + 烧录）→ {@link FfmpegService}</li>
 *   <li>upload → 平台上传器（后续阶段接入）</li>
 * </ul>
 * 状态迁移仍由 {@link TaskService} 的状态机统一裁决。</p>
 */
@Service
public class TaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);

    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final TaskEventPublisher eventPublisher;
    private final YtDlpClient ytDlp;
    private final FfmpegService ffmpeg;
    private final CookieService cookieService;
    private final com.y2a.server.uploader.UploadDispatcher uploadDispatcher;
    private final com.y2a.server.ai.AiClient aiClient;
    private final com.y2a.server.ai.MetadataTranslator metadataTranslator;
    private final com.y2a.server.ai.TagGenerator tagGenerator;
    private final com.y2a.server.ai.PartitionRecommender partitionRecommender;

    @Value("${app.storage.cookies:./storage/cookies}")
    private String cookiesDir;

    /** taskId → 取消标记。 */
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    public TaskOrchestrator(TaskService taskService, TaskRepository taskRepository,
                            TaskEventPublisher eventPublisher, YtDlpClient ytDlp,
                            FfmpegService ffmpeg, CookieService cookieService,
                            com.y2a.server.uploader.UploadDispatcher uploadDispatcher,
                            com.y2a.server.ai.AiClient aiClient,
                            com.y2a.server.ai.MetadataTranslator metadataTranslator,
                            com.y2a.server.ai.TagGenerator tagGenerator,
                            com.y2a.server.ai.PartitionRecommender partitionRecommender) {
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.eventPublisher = eventPublisher;
        this.ytDlp = ytDlp;
        this.ffmpeg = ffmpeg;
        this.cookieService = cookieService;
        this.uploadDispatcher = uploadDispatcher;
        this.aiClient = aiClient;
        this.metadataTranslator = metadataTranslator;
        this.tagGenerator = tagGenerator;
        this.partitionRecommender = partitionRecommender;
    }

    public AtomicBoolean cancelFlag(String taskId) {
        return cancelFlags.computeIfAbsent(taskId, k -> new AtomicBoolean(false));
    }

    public void requestCancel(String taskId) {
        cancelFlag(taskId).set(true);
    }

    /** 异步执行任务（从当前状态继续）。 */
    @Async("taskExecutor")
    public void runAsync(String taskId) {
        try {
            run(taskId);
        } catch (Exception e) {
            log.error("任务 {} 执行失败", taskId, e);
            fail(taskId, "任务执行失败: " + e.getMessage());
        } finally {
            cancelFlags.remove(taskId);
        }
    }

    /** 同步执行（供测试与强制重跑）。 */
    public void run(String taskId) {
        Task task = taskService.get(taskId);
        TaskStatus status = task.statusEnum();
        log.info("任务 {} 开始执行，当前状态 {}", taskId, status.value());

        // 1) 元信息
        if (status == TaskStatus.PENDING) {
            fetchInfo(taskId);
            task = taskService.get(taskId);
            status = task.statusEnum();
        }

        // 2) 下载
        if (status == TaskStatus.PENDING || status == TaskStatus.DOWNLOADING) {
            download(taskId);
            task = taskService.get(taskId);
            status = task.statusEnum();
        }

        // 3) 字幕（可选）
        if (status == TaskStatus.DOWNLOADED) {
            maybeFetchSubtitle(taskId);
            task = taskService.get(taskId);
        }

        // 4) AI 元信息（翻译 / 标签 / 分区）
        runAiStage(taskId);

        // 5) 转码/烧录（可选，由配置决定）
        Task finalTask = taskService.get(taskId);
        if (shouldEncode(finalTask)) {
            encode(taskId);
        }

        // 6) 准备上传
        Task afterEncode = taskService.get(taskId);
        if (afterEncode.statusEnum() == TaskStatus.DOWNLOADED
                || afterEncode.statusEnum() == TaskStatus.ENCODING_VIDEO) {
            taskService.changeStatusTo(taskId, TaskStatus.AWAITING_REVIEW);
            log.info("任务 {} 处理完成，进入人工审核", taskId);
        }
    }

    /**
     * AI 阶段：翻译标题/简介 → 生成标签 → 推荐分区。
     * 任何一步失败都不阻塞主流程，只记录警告。
     */
    private void runAiStage(String taskId) {
        Task task = taskService.get(taskId);
        boolean needTranslate = configBool("TRANSLATE_TITLE", false)
                || configBool("TRANSLATE_DESCRIPTION", false);
        boolean needTags = configBool("GENERATE_TAGS", false);
        boolean needPartition = configBool("RECOMMEND_PARTITION", false);

        if (!needTranslate && !needTags && !needPartition) {
            return;
        }
        if (!aiClient.globalConfig().usable()) {
            log(taskId, "AI 未配置，跳过 AI 阶段");
            return;
        }

        // ---- 翻译 ----
        if (needTranslate) {
            taskService.changeStatusTo(taskId, TaskStatus.TRANSLATING);
            log(taskId, "开始 AI 翻译标题与简介");
            var limits = metadataLimits(task);
            String targetLang = configStr("SUBTITLE_TARGET_LANGUAGE", "zh");
            try {
                var result = metadataTranslator.translate(
                        task.getVideoTitleOriginal(),
                        task.getDescriptionOriginal(),
                        targetLang,
                        limits.titleLimit(), limits.descLimit(),
                        configBool("TRANSLATE_TITLE", false),
                        configBool("TRANSLATE_DESCRIPTION", false));
                Task fresh = taskService.get(taskId);
                if (result.titleTranslated()) fresh.setVideoTitleTranslated(result.title());
                if (result.descriptionTranslated()) fresh.setDescriptionTranslated(result.description());
                taskRepository.updateById(fresh);
                for (String w : result.warnings()) log(taskId, "翻译提示: " + w);
                log(taskId, "翻译完成 title=" + result.titleTranslated()
                        + " desc=" + result.descriptionTranslated());
            } catch (Exception e) {
                log(taskId, "翻译失败（继续）: " + e.getMessage());
            }
            task = taskService.get(taskId);
        }

        // ---- 标签 ----
        if (needTags) {
            taskService.changeStatusTo(taskId, TaskStatus.TAGGING);
            log(taskId, "开始生成标签");
            try {
                List<String> avoid = presetTags();
                var tags = tagGenerator.generate(
                        task.getVideoTitleTranslated() != null
                                ? task.getVideoTitleTranslated() : task.getVideoTitleOriginal(),
                        task.getDescriptionTranslated() != null
                                ? task.getDescriptionTranslated() : task.getDescriptionOriginal(),
                        avoid, taskId);
                if (!tags.isEmpty()) {
                    Task fresh = taskService.get(taskId);
                    // 预设标签优先，AI 标签补齐
                    List<String> merged = new ArrayList<>(avoid);
                    for (String t : tags) {
                        if (merged.stream().noneMatch(x -> x.equalsIgnoreCase(t))) merged.add(t);
                    }
                    fresh.setTagsGenerated(merged);
                    taskRepository.updateById(fresh);
                    log(taskId, "标签已写入: " + merged);
                }
            } catch (Exception e) {
                log(taskId, "标签生成失败（继续）: " + e.getMessage());
            }
            task = taskService.get(taskId);
        }

        // ---- 分区 ----
        if (needPartition) {
            taskService.changeStatusTo(taskId, TaskStatus.PARTITIONING);
            log(taskId, "开始推荐分区");
            try {
                UploadTarget target = task.targetEnum();
                List<String> platforms = target.platforms().stream()
                        .map(UploadTarget::value).toList();
                String title = task.getVideoTitleTranslated() != null
                        ? task.getVideoTitleTranslated() : task.getVideoTitleOriginal();
                String desc = task.getDescriptionTranslated() != null
                        ? task.getDescriptionTranslated() : task.getDescriptionOriginal();

                for (String platform : platforms) {
                    var sel = partitionRecommender.recommend(platform, title, desc,
                            task.getTagsGenerated());
                    if (!sel.found()) {
                        log(taskId, platform + " 分区推荐未命中: " + sel.reason());
                        continue;
                    }
                    Task fresh = taskService.get(taskId);
                    if ("acfun".equals(platform)) {
                        fresh.setRecommendedPartitionIdAcfun(sel.id());
                        if (fresh.getSelectedPartitionIdAcfun() == null) {
                            fresh.setSelectedPartitionIdAcfun(sel.id());
                        }
                    } else if ("bilibili".equals(platform)) {
                        fresh.setRecommendedPartitionIdBilibili(sel.id());
                        if (fresh.getSelectedPartitionIdBilibili() == null) {
                            fresh.setSelectedPartitionIdBilibili(sel.id());
                        }
                    }
                    taskRepository.updateById(fresh);
                    log(taskId, String.format("%s 分区推荐: %s(%s) confidence=%.2f",
                            platform, sel.name(), sel.id(), sel.confidence()));
                }
            } catch (Exception e) {
                log(taskId, "分区推荐失败（继续）: " + e.getMessage());
            }
        }
    }

    /** 按目标平台取元信息字数上限。 */
    private record MetadataLimits(int titleLimit, int descLimit) {
    }

    private MetadataLimits metadataLimits(Task task) {
        return switch (task.targetEnum()) {
            case BILIBILI -> new MetadataLimits(80, 2000);
            case DOUYIN -> new MetadataLimits(30, 1000);
            default -> new MetadataLimits(50, 1000);
        };
    }

    /** 读取预设标签（换行/逗号/顿号/分号分隔）。 */
    private List<String> presetTags() {
        if (!configBool("PRESET_TAGS_ENABLED", false)) return List.of();
        String raw = configStr("PRESET_TAGS", "");
        List<String> result = new ArrayList<>();
        for (String part : raw.split("[\\n\\r,，、;；]+")) {
            String tag = part.trim();
            if (!tag.isEmpty() && result.stream().noneMatch(t -> t.equalsIgnoreCase(tag))) {
                result.add(tag);
            }
            if (result.size() >= 12) break;
        }
        return result;
    }

    /** 异步执行上传阶段（供「强制上传」与 AUTO_MODE 调用）。 */
    @Async("taskExecutor")
    public void uploadAsync(String taskId) {
        try {
            upload(taskId);
        } catch (Exception e) {
            log.error("任务 {} 上传失败", taskId, e);
            fail(taskId, "上传失败: " + e.getMessage());
        } finally {
            cancelFlags.remove(taskId);
        }
    }

    /** 上传阶段：按目标平台分发，成功写回响应并置 completed。 */
    public void upload(String taskId) {
        Task task = taskService.get(taskId);
        if (task.getVideoPathLocal() == null
                || !Files.isRegularFile(Paths.get(task.getVideoPathLocal()))) {
            throw new IllegalStateException("视频文件不存在，无法上传");
        }
        taskService.changeStatusTo(taskId, TaskStatus.UPLOADING);
        log(taskId, "开始上传到 " + task.targetEnum().label());

        var outcome = uploadDispatcher.upload(task,
                pct -> updateProgress(taskId, String.format("%.1f%%", pct)),
                line -> log(taskId, line),
                cancelFlag(taskId));

        if (!outcome.success()) {
            fail(taskId, outcome.message());
            return;
        }
        Task fresh = taskService.get(taskId);
        fresh.setStatus(TaskStatus.COMPLETED.value());
        fresh.setUploadProgress(null);
        fresh.setErrorMessage(null);
        taskRepository.updateById(fresh);
        updateProgress(taskId, null);
        log(taskId, "上传完成: " + outcome.message());
        eventPublisher.published(taskId, "task_completed", Map.of("detail", outcome.message()));
    }

    // ---------------- 各阶段实现 ----------------

    /** 阶段：抓取元信息。 */
    private void fetchInfo(String taskId) {
        Task task = taskService.get(taskId);
        log(taskId, "开始获取视频信息");
        Path cookies = youtubeCookies();
        var meta = ytDlp.fetchMetadata(task.getYoutubeUrl(), cookies, line -> log(taskId, line));

        task.setVideoTitleOriginal(meta.title());
        task.setDescriptionOriginal(meta.description());
        taskRepository.updateById(task);
        log(taskId, "元信息获取完成: " + truncate(meta.title(), 60));
        eventPublisher.published(taskId, "task_updated", Map.of("stage", "fetch_info"));
    }

    /** 阶段：下载视频。 */
    private void download(String taskId) {
        Task task = taskService.get(taskId);
        if (task.statusEnum() == TaskStatus.PENDING) {
            taskService.changeStatus(taskId, TaskStatus.DOWNLOADING);
        }
        log(taskId, "开始下载视频");
        Path cookies = youtubeCookies();
        int maxHeight = resolveMaxHeight();

        var result = ytDlp.download(task.getYoutubeUrl(), taskId, cookies, maxHeight,
                pct -> updateProgress(taskId, String.format("%.1f%%", pct)),
                line -> log(taskId, line),
                cancelFlag(taskId));

        Task fresh = taskService.get(taskId);
        fresh.setVideoPathLocal(result.videoPath().toString());
        if (result.coverPath() != null) fresh.setCoverPathLocal(result.coverPath().toString());
        if (result.metadataJsonPath() != null) {
            fresh.setMetadataJsonPathLocal(result.metadataJsonPath().toString());
        }
        taskRepository.updateById(fresh);

        if (result.coverPath() == null) {
            Path generated = ffmpeg.extractCover(result.videoPath(), taskId);
            if (generated != null) {
                Task t2 = taskService.get(taskId);
                t2.setCoverPathLocal(generated.toString());
                taskRepository.updateById(t2);
                log(taskId, "已从视频生成封面");
            }
        }

        taskService.changeStatusTo(taskId, TaskStatus.DOWNLOADED);
        updateProgress(taskId, null);
        log(taskId, "下载完成: " + result.videoPath().getFileName());
        eventPublisher.published(taskId, "task_updated", Map.of("stage", "download_video"));
    }

    /** 阶段：字幕（可选，失败不影响主流程）。 */
    private void maybeFetchSubtitle(String taskId) {
        Task task = taskService.get(taskId);
        if (!configBool("SUBTITLE_TRANSLATION_ENABLED", false)
                && !configBool("YOUTUBE_AUTO_GENERATED_SUBTITLES_ENABLED", false)) {
            return;
        }
        try {
            log(taskId, "尝试获取字幕");
            String lang = configStr("SUBTITLE_SOURCE_LANGUAGE", "auto");
            boolean auto = configBool("YOUTUBE_AUTO_GENERATED_SUBTITLES_ENABLED", true);
            var sub = ytDlp.downloadSubtitles(task.getYoutubeUrl(), taskId,
                    youtubeCookies(), lang, auto, line -> log(taskId, line));
            if (sub.subtitlePath() != null) {
                Task t = taskService.get(taskId);
                t.setSubtitlePathOriginal(sub.subtitlePath().toString());
                t.setSubtitleLanguageDetected(sub.language());
                taskRepository.updateById(t);
                log(taskId, "字幕已下载: " + sub.subtitlePath().getFileName());
            }
        } catch (Exception e) {
            log(taskId, "字幕获取失败（继续）: " + e.getMessage());
        }
    }

    /** 阶段：转码 + 烧录。 */
    private void encode(String taskId) {
        Task task = taskService.get(taskId);
        String videoPath = task.getVideoPathLocal();
        if (videoPath == null || !Files.isRegularFile(Paths.get(videoPath))) {
            log(taskId, "无视频文件，跳过转码");
            return;
        }
        taskService.changeStatusTo(taskId, TaskStatus.ENCODING_VIDEO);
        log(taskId, "开始转码");

        Path subtitle = null;
        if (configBool("SUBTITLE_EMBED_IN_VIDEO", false) && task.getSubtitlePathOriginal() != null) {
            subtitle = Paths.get(task.getSubtitlePathOriginal());
        }
        double crf = configDouble("VIDEO_QUALITY_VALUE", 23.0);
        String codec = configStr("VIDEO_CPU_CODEC", "x264");

        var result = ffmpeg.transcode(Paths.get(videoPath), subtitle, taskId, crf, codec,
                pct -> updateProgress(taskId, String.format("%.1f%%", pct)),
                line -> log(taskId, line),
                cancelFlag(taskId));

        Task fresh = taskService.get(taskId);
        fresh.setVideoPathLocal(result.outputPath().toString());
        taskRepository.updateById(fresh);
        updateProgress(taskId, null);
        log(taskId, "转码完成" + (result.burnedSubtitle() ? "（已烧录字幕）" : ""));
    }

    // ---------------- 工具 ----------------

    private boolean shouldEncode(Task task) {
        // 只有明确要求转码/烧录时才执行，避免无谓重编码
        return configBool("SUBTITLE_EMBED_IN_VIDEO", false)
                && "downloaded".equals(task.getStatus())
                && task.getSubtitlePathOriginal() != null;
    }

    private Path youtubeCookies() {
        Path p = Paths.get(cookiesDir).resolve("yt_cookies.txt");
        return Files.isRegularFile(p) ? p : null;
    }

    private int resolveMaxHeight() {
        String mode = configStr("YOUTUBE_DOWNLOAD_QUALITY_MODE", "highest");
        if (!"manual".equalsIgnoreCase(mode)) return 0;
        try {
            return Integer.parseInt(configStr("YOUTUBE_DOWNLOAD_MAX_HEIGHT", "1080"));
        } catch (NumberFormatException e) {
            return 1080;
        }
    }

    private void updateProgress(String taskId, String progress) {
        try {
            Task t = taskService.get(taskId);
            t.setUploadProgress(progress);
            taskRepository.updateById(t);
            eventPublisher.published(taskId, "task_progress",
                    progress == null ? Map.of() : Map.of("progress", progress));
        } catch (Exception ignored) {
        }
    }

    private void fail(String taskId, String message) {
        try {
            Task t = taskService.get(taskId);
            t.setStatus(TaskStatus.FAILED.value());
            t.setErrorMessage(message);
            t.setUploadProgress(null);
            taskRepository.updateById(t);
            eventPublisher.published(taskId, "task_failed", Map.of("error", message));
        } catch (Exception ignored) {
        }
    }

    private void log(String taskId, String message) {
        Logger taskLogger = LoggerFactory.getLogger("task." + taskId);
        taskLogger.info(message);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // 配置读取（由 ConfigService 提供；此处轻量封装便于替换）
    @org.springframework.beans.factory.annotation.Autowired
    private ConfigService configService;

    private boolean configBool(String key, boolean def) {
        Object v = configService.get(key, def);
        if (v instanceof Boolean b) return b;
        return v != null && java.util.List.of("true", "1", "on", "yes")
                .contains(String.valueOf(v).toLowerCase());
    }

    private String configStr(String key, String def) {
        Object v = configService.get(key, def);
        return v == null ? def : String.valueOf(v);
    }

    private double configDouble(String key, double def) {
        Object v = configService.get(key, def);
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }
}

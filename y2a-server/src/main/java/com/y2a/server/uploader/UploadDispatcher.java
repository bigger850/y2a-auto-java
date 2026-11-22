package com.y2a.server.uploader;

import com.y2a.server.common.constant.UploadTarget;
import com.y2a.server.domain.task.Task;
import com.y2a.server.repository.TaskRepository;
import com.y2a.server.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 上传分发器（策略模式）。
 *
 * <p>按任务的 upload_target 选择上传器；both 时串行执行 AcFun → bilibili。
 * 每个平台成功后立即把结果写回任务字段，保证幂等与断点续跑。</p>
 */
@Component
public class UploadDispatcher {

    private static final Logger log = LoggerFactory.getLogger(UploadDispatcher.class);

    private final AcfunUploader acfun;
    private final com.y2a.server.uploader.bili.BilibiliUploader bilibili;
    private final DouyinPublisher douyin;
    private final TaskRepository taskRepository;
    private final ConfigService configService;

    @Value("${app.storage.cookies:./storage/cookies}")
    private String cookiesDir;

    public UploadDispatcher(AcfunUploader acfun,
                            com.y2a.server.uploader.bili.BilibiliUploader bilibili,
                            DouyinPublisher douyin,
                            TaskRepository taskRepository, ConfigService configService) {
        this.acfun = acfun;
        this.bilibili = bilibili;
        this.douyin = douyin;
        this.taskRepository = taskRepository;
        this.configService = configService;
    }

    /** 上传结果汇总。 */
    public record UploadOutcome(boolean success, String message, Map<String, Object> detail) {}

    /**
     * 执行上传（按目标平台分发）。
     */
    public UploadOutcome upload(Task task, Consumer<Double> progress,
                                Consumer<String> logger, AtomicBoolean cancelFlag) {
        UploadTarget target = task.targetEnum();
        List<UploadTarget> platforms = target.platforms();

        StringBuilder summary = new StringBuilder();
        for (UploadTarget platform : platforms) {
            if (alreadyUploaded(task, platform)) {
                summary.append(platform.label()).append(":已有结果; ");
                continue;
            }
            UploadOutcome outcome = switch (platform) {
                case ACFUN -> uploadAcfun(task, progress, logger, cancelFlag);
                case BILIBILI -> uploadBilibili(task, progress, logger, cancelFlag);
                case DOUYIN -> uploadDouyin(task, progress, logger, cancelFlag);
                default -> new UploadOutcome(false, "未知平台", Map.of());
            };
            summary.append(platform.label()).append(": ")
                    .append(outcome.success() ? "成功" : outcome.message()).append("; ");
            if (!outcome.success()) {
                return new UploadOutcome(false, summary.toString(), Map.of());
            }
            task = taskRepository.selectById(task.getId()); // 取回最新（含刚写入的响应）
        }
        return new UploadOutcome(true, summary.toString(), Map.of());
    }

    private UploadOutcome uploadAcfun(Task task, Consumer<Double> progress,
                                      Consumer<String> logger, AtomicBoolean cancelFlag) {
        Path video = pathOf(task.getVideoPathLocal());
        if (video == null) {
            return new UploadOutcome(false, "视频文件不存在", Map.of());
        }
        Path cover = pathOf(task.getCoverPathLocal());
        Path cookieFile = Paths.get(cookiesDir).resolve("ac_cookies.json");

        String partition = configService.getString("FIXED_PARTITION_ID", "");
        if (partition == null || partition.isBlank()) {
            partition = task.getSelectedPartitionId() != null
                    ? task.getSelectedPartitionId() : "1";
        }

        var result = acfun.upload(video, cover,
                task.displayTitle(), task.getDescriptionTranslated(),
                task.getTagsGenerated(), partition, cookieFile,
                progress, logger, cancelFlag);

        if (!result.success()) {
            return new UploadOutcome(false, result.error(), Map.of());
        }
        Task fresh = taskRepository.selectById(task.getId());
        fresh.setAcfunUploadResponse(Map.of(
                "ac_number", result.acNumber(),
                "title", task.displayTitle() == null ? "" : task.displayTitle(),
                "cover_url", result.coverUrl() == null ? "" : result.coverUrl(),
                "url", "https://www.acfun.cn/v/ac" + result.acNumber()));
        taskRepository.updateById(fresh);
        return new UploadOutcome(true, "AcFun 投稿成功 AC" + result.acNumber(), Map.of());
    }

    private UploadOutcome uploadBilibili(Task task, Consumer<Double> progress,
                                         Consumer<String> logger, AtomicBoolean cancelFlag) {
        Path video = pathOf(task.getVideoPathLocal());
        if (video == null) {
            return new UploadOutcome(false, "视频文件不存在", Map.of());
        }
        Path cover = pathOf(task.getCoverPathLocal());
        Path cookieFile = Paths.get(cookiesDir).resolve("bili_cookies.json");

        String tid = configService.getString("FIXED_PARTITION_ID_BILIBILI", "");
        if (tid == null || tid.isBlank()) {
            tid = task.getSelectedPartitionId() != null ? task.getSelectedPartitionId() : "21";
        }

        var result = bilibili.upload(video, cover,
                task.displayTitle(), task.getDescriptionTranslated(),
                task.getTagsGenerated(), tid, cookieFile,
                progress, logger, cancelFlag);

        if (!result.success()) {
            return new UploadOutcome(false, result.error(), Map.of());
        }
        Task fresh = taskRepository.selectById(task.getId());
        fresh.setBilibiliUploadResponse(Map.of(
                "bvid", result.bvid() == null ? "" : result.bvid(),
                "aid", result.aid() == null ? "" : result.aid(),
                "title", task.displayTitle() == null ? "" : task.displayTitle(),
                "url", "https://www.bilibili.com/video/" + (result.bvid() == null ? "" : result.bvid())));
        taskRepository.updateById(fresh);
        return new UploadOutcome(true, "bilibili 投稿成功 " + result.bvid(), Map.of());
    }

    private UploadOutcome uploadDouyin(Task task, Consumer<Double> progress,
                                       Consumer<String> logger, AtomicBoolean cancelFlag) {
        Path video = pathOf(task.getVideoPathLocal());
        if (video == null) {
            return new UploadOutcome(false, "视频文件不存在", Map.of());
        }
        Path cover = pathOf(task.getCoverPathLocal());
        Path cookieFile = Paths.get(cookiesDir).resolve("douyin_cookies.json");
        Path shots = Paths.get(cookiesDir).getParent() == null
                ? Paths.get("logs/douyin") : Paths.get(cookiesDir).getParent().resolve("logs/douyin");

        boolean headless = boolConfig("DOUYIN_HEADLESS", true);
        boolean draftOnly = boolConfig("DOUYIN_SAVE_DRAFT_ONLY", false);

        var result = douyin.publish(video, cover, task.displayTitle(), task.getTagsGenerated(),
                cookieFile, headless, draftOnly, shots, progress, logger, cancelFlag);

        if (!result.success()) {
            return new UploadOutcome(false, result.error(), Map.of());
        }
        Task fresh = taskRepository.selectById(task.getId());
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("platform", "douyin");
        resp.put("item_id", result.itemId() == null ? "" : result.itemId());
        resp.put("title", task.displayTitle() == null ? "" : task.displayTitle());
        resp.put("url", result.url() == null ? "" : result.url());
        resp.put("message", "抖音发布成功");
        fresh.setDouyinUploadResponse(resp);
        taskRepository.updateById(fresh);
        return new UploadOutcome(true, "抖音发布成功", Map.of());
    }

    private boolean boolConfig(String key, boolean def) {
        Object v = configService.get(key, def);
        if (v instanceof Boolean b) return b;
        return v != null && List.of("true", "1", "on", "yes")
                .contains(String.valueOf(v).toLowerCase());
    }

    private boolean alreadyUploaded(Task task, UploadTarget platform) {
        return switch (platform) {
            case ACFUN -> task.getAcfunUploadResponse() != null && !task.getAcfunUploadResponse().isEmpty();
            case BILIBILI -> task.getBilibiliUploadResponse() != null && !task.getBilibiliUploadResponse().isEmpty();
            case DOUYIN -> task.getDouyinUploadResponse() != null && !task.getDouyinUploadResponse().isEmpty();
            default -> false;
        };
    }

    private static Path pathOf(String p) {
        if (p == null || p.isBlank()) return null;
        Path path = Paths.get(p);
        return Files.isRegularFile(path) ? path : null;
    }
}

package com.y2a.server.uploader;

import com.y2a.server.common.constant.UploadTarget;
import com.y2a.server.domain.task.Task;
import com.y2a.server.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 上传分发器（策略模式 + 注册表）。
 *
 * <p>本类只做三件事：按 {@link UploadTarget} 取出策略、串行执行、
 * 把成功结果写回任务。**不含任何平台特有逻辑**——新增平台只需实现
 * {@link PlatformUploader} 并标注 {@code @Component}，Spring 会自动注入。</p>
 */
@Component
public class UploadDispatcher {

    private static final Logger log = LoggerFactory.getLogger(UploadDispatcher.class);

    private final Map<UploadTarget, PlatformUploader> strategies;
    private final UploadContextFactory contextFactory;
    private final TaskRepository taskRepository;

    public UploadDispatcher(List<PlatformUploader> uploaders,
                            UploadContextFactory contextFactory,
                            TaskRepository taskRepository) {
        // 注册表：平台 → 策略。重复定义会在启动时暴露，而非运行时静默覆盖
        this.strategies = uploaders.stream().collect(Collectors.toMap(
                PlatformUploader::platform,
                Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("平台上传器重复注册: " + a.platform());
                },
                () -> new EnumMap<>(UploadTarget.class)));
        this.contextFactory = contextFactory;
        this.taskRepository = taskRepository;
        log.info("已注册上传策略: {}", strategies.keySet());
    }

    /** 上传结果汇总。 */
    public record UploadOutcome(boolean success, String message, Map<String, Object> detail) {
    }

    /**
     * 执行上传：按任务的 upload_target 展开平台列表，逐个执行。
     * 每个平台成功后立即写库（幂等），失败则中断并返回已完成的汇总。
     */
    public UploadOutcome upload(Task task, Consumer<Double> progress,
                                Consumer<String> logger, AtomicBoolean cancelFlag) {
        UploadTarget target = task.targetEnum();
        List<UploadTarget> platforms = target.platforms();

        StringBuilder summary = new StringBuilder();
        Task current = task;

        for (UploadTarget platform : platforms) {
            PlatformUploader strategy = strategies.get(platform);
            if (strategy == null) {
                summary.append(platform.label()).append(": 未实现; ");
                return new UploadOutcome(false, summary.toString(), Map.of());
            }
            if (strategy.alreadyUploaded(current)) {
                summary.append(platform.label()).append(": 已有结果; ");
                continue;
            }

            var ctx = contextFactory.build(current, platform.value(), progress, logger, cancelFlag);
            PlatformUploader.UploadResult result;
            try {
                result = strategy.upload(ctx);
            } catch (Exception e) {
                log.error("[{}] 上传异常", platform.label(), e);
                summary.append(platform.label()).append(": 异常 ").append(e.getMessage()).append("; ");
                return new UploadOutcome(false, summary.toString(), Map.of());
            }

            summary.append(platform.label()).append(": ")
                    .append(result.success() ? "成功" : result.message()).append("; ");

            if (!result.success()) {
                return new UploadOutcome(false, summary.toString(), Map.of());
            }

            // 成功：写回响应字段，再取最新实体供下一个平台使用
            Task fresh = taskRepository.selectById(current.getId());
            strategy.applyResponse(fresh, result.response());
            taskRepository.updateById(fresh);
            current = fresh;
        }
        return new UploadOutcome(true, summary.toString(), Map.of());
    }

    /** 已注册的平台（供健康检查/诊断）。 */
    public java.util.Set<UploadTarget> registeredPlatforms() {
        return strategies.keySet();
    }
}

package com.y2a.server.uploader;

import com.y2a.server.common.constant.UploadTarget;
import com.y2a.server.domain.task.Task;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 平台上传器策略接口（Strategy）。
 *
 * <p>每个平台实现一份，负责：解析该平台所需的 Cookie 路径与分区参数、
 * 调用底层协议客户端、把响应写回任务字段。`UploadDispatcher` 只按
 * {@link UploadTarget} 从注册表取出对应策略，不含任何平台分支。</p>
 *
 * <p>新增平台时只需实现本接口并标注 {@code @Component}，分发器自动收录。</p>
 */
public interface PlatformUploader {

    /** 本策略负责的平台。 */
    UploadTarget platform();

    /**
     * 执行一次上传。
     *
     * @param ctx 上传上下文（任务、文件路径、进度与日志回调、取消标记）
     * @return 上传结果；失败时 message 为可直接展示的错误原因
     */
    UploadResult upload(UploadContext ctx);

    /** 上传上下文：把各平台都要用的东西收拢，避免每个实现重复组装。 */
    record UploadContext(Task task,
                         java.nio.file.Path video,
                         java.nio.file.Path cover,
                         java.nio.file.Path cookieFile,
                         Consumer<Double> progress,
                         Consumer<String> logger,
                         AtomicBoolean cancelFlag) {
    }

    /** 上传结果。 */
    record UploadResult(boolean success, String message, Map<String, Object> response) {
        public static UploadResult ok(String message, Map<String, Object> response) {
            return new UploadResult(true, message, response == null ? Map.of() : response);
        }

        public static UploadResult fail(String message) {
            return new UploadResult(false, message, Map.of());
        }
    }

    /** 平台展示名（用于汇总日志）。 */
    default String label() {
        return platform().label();
    }

    /** 该平台要写入任务的响应字段名，供分发器统一写库。 */
    default String responseField() {
        return switch (platform()) {
            case ACFUN -> "acfunUploadResponse";
            case BILIBILI -> "bilibiliUploadResponse";
            case DOUYIN -> "douyinUploadResponse";
            default -> throw new IllegalStateException("未知平台: " + platform());
        };
    }

    /** 该平台是否已有上传结果（幂等跳过用）。 */
    default boolean alreadyUploaded(Task task) {
        Map<String, Object> existing = switch (platform()) {
            case ACFUN -> task.getAcfunUploadResponse();
            case BILIBILI -> task.getBilibiliUploadResponse();
            case DOUYIN -> task.getDouyinUploadResponse();
            default -> null;
        };
        return existing != null && !existing.isEmpty();
    }

    /** 便捷方法：把结果按平台写入任务实体（由分发器调用）。 */
    default void applyResponse(Task task, Map<String, Object> response) {
        switch (platform()) {
            case ACFUN -> task.setAcfunUploadResponse(response);
            case BILIBILI -> task.setBilibiliUploadResponse(response);
            case DOUYIN -> task.setDouyinUploadResponse(response);
            default -> throw new IllegalStateException("未知平台: " + platform());
        }
    }

    /** 目标平台展开后的列表（both → [acfun, bilibili]）。 */
    static List<UploadTarget> expand(UploadTarget target) {
        return target.platforms();
    }
}

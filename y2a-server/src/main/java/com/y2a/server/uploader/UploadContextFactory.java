package com.y2a.server.uploader;

import com.y2a.server.domain.task.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 上传上下文工厂：统一解析视频/封面/Cookie 路径，避免各平台策略重复。
 */
@Component
public class UploadContextFactory {

    /** 平台 → Cookie 文件名。 */
    private static final Map<String, String> COOKIE_FILES = Map.of(
            "acfun", "ac_cookies.json",
            "bilibili", "bili_cookies.json",
            "douyin", "douyin_cookies.json");

    @Value("${app.storage.cookies:./storage/cookies}")
    private String cookiesDir;

    /** 为指定平台构建上下文。 */
    public PlatformUploader.UploadContext build(Task task, String platform,
                                                Consumer<Double> progress,
                                                Consumer<String> logger,
                                                AtomicBoolean cancelFlag) {
        return new PlatformUploader.UploadContext(
                task,
                existingPath(task.getVideoPathLocal()),
                existingPath(task.getCoverPathLocal()),
                cookieFile(platform),
                progress, logger, cancelFlag);
    }

    /** Cookie 文件路径（即使不存在也返回路径，由策略自行校验并给出友好错误）。 */
    public Path cookieFile(String platform) {
        String name = COOKIE_FILES.get(platform.toLowerCase());
        return name == null ? null : Paths.get(cookiesDir).resolve(name);
    }

    /** 便捷方法：视频文件存在性判断。 */
    public static Path existingPath(String p) {
        if (p == null || p.isBlank()) return null;
        Path path = Paths.get(p);
        return Files.isRegularFile(path) ? path : null;
    }
}

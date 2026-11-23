package com.y2a.server.uploader.strategy;

import com.y2a.server.common.constant.UploadTarget;
import com.y2a.server.service.ConfigService;
import com.y2a.server.uploader.DouyinPublisher;
import com.y2a.server.uploader.PlatformUploader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 抖音上传策略。 */
@Component
public class DouyinUploadStrategy implements PlatformUploader {

    private final DouyinPublisher publisher;
    private final ConfigService configService;

    @Value("${app.storage.cookies:./storage/cookies}")
    private String cookiesDir;

    public DouyinUploadStrategy(DouyinPublisher publisher, ConfigService configService) {
        this.publisher = publisher;
        this.configService = configService;
    }

    @Override
    public UploadTarget platform() {
        return UploadTarget.DOUYIN;
    }

    @Override
    public UploadResult upload(UploadContext ctx) {
        if (ctx.video() == null) {
            return UploadResult.fail("视频文件不存在");
        }
        Path shots = Paths.get(cookiesDir).getParent() == null
                ? Paths.get("logs/douyin")
                : Paths.get(cookiesDir).getParent().resolve("logs/douyin");

        boolean headless = boolConfig("DOUYIN_HEADLESS", true);
        boolean draftOnly = boolConfig("DOUYIN_SAVE_DRAFT_ONLY", false);

        var result = publisher.publish(ctx.video(), ctx.cover(), ctx.task().displayTitle(),
                ctx.task().getTagsGenerated(), ctx.cookieFile(), headless, draftOnly, shots,
                ctx.progress(), ctx.logger(), ctx.cancelFlag());

        if (!result.success()) {
            return UploadResult.fail(result.error());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("platform", "douyin");
        response.put("item_id", result.itemId() == null ? "" : result.itemId());
        response.put("title", ctx.task().displayTitle() == null ? "" : ctx.task().displayTitle());
        response.put("url", result.url() == null ? "" : result.url());
        response.put("message", "抖音发布成功");
        return UploadResult.ok("抖音发布成功", response);
    }

    private boolean boolConfig(String key, boolean def) {
        Object v = configService.get(key, def);
        if (v instanceof Boolean b) return b;
        return v != null && List.of("true", "1", "on", "yes")
                .contains(String.valueOf(v).toLowerCase());
    }
}

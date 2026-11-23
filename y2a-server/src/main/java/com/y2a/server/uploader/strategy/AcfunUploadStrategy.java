package com.y2a.server.uploader.strategy;

import com.y2a.server.common.constant.UploadTarget;
import com.y2a.server.service.ConfigService;
import com.y2a.server.uploader.AcfunUploader;
import com.y2a.server.uploader.PlatformUploader;
import org.springframework.stereotype.Component;

import java.util.Map;

/** AcFun 上传策略。 */
@Component
public class AcfunUploadStrategy implements PlatformUploader {

    private final AcfunUploader uploader;
    private final ConfigService configService;

    public AcfunUploadStrategy(AcfunUploader uploader, ConfigService configService) {
        this.uploader = uploader;
        this.configService = configService;
    }

    @Override
    public UploadTarget platform() {
        return UploadTarget.ACFUN;
    }

    @Override
    public UploadResult upload(UploadContext ctx) {
        if (ctx.video() == null) {
            return UploadResult.fail("视频文件不存在");
        }
        String partition = configService.getString("FIXED_PARTITION_ID", "");
        if (partition == null || partition.isBlank()) {
            partition = ctx.task().getSelectedPartitionId() != null
                    ? ctx.task().getSelectedPartitionId() : "1";
        }

        var result = uploader.upload(ctx.video(), ctx.cover(),
                ctx.task().displayTitle(), ctx.task().getDescriptionTranslated(),
                ctx.task().getTagsGenerated(), partition, ctx.cookieFile(),
                ctx.progress(), ctx.logger(), ctx.cancelFlag());

        if (!result.success()) {
            return UploadResult.fail(result.error());
        }
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("ac_number", result.acNumber());
        response.put("title", ctx.task().displayTitle() == null ? "" : ctx.task().displayTitle());
        response.put("cover_url", result.coverUrl() == null ? "" : result.coverUrl());
        response.put("url", "https://www.acfun.cn/v/ac" + result.acNumber());
        return UploadResult.ok("AcFun 投稿成功 AC" + result.acNumber(), response);
    }
}

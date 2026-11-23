package com.y2a.server.uploader.strategy;

import com.y2a.server.common.constant.UploadTarget;
import com.y2a.server.service.ConfigService;
import com.y2a.server.uploader.PlatformUploader;
import com.y2a.server.uploader.bili.BilibiliUploader;
import org.springframework.stereotype.Component;

import java.util.Map;

/** bilibili 上传策略。 */
@Component
public class BilibiliUploadStrategy implements PlatformUploader {

    private final BilibiliUploader uploader;
    private final ConfigService configService;

    public BilibiliUploadStrategy(BilibiliUploader uploader, ConfigService configService) {
        this.uploader = uploader;
        this.configService = configService;
    }

    @Override
    public UploadTarget platform() {
        return UploadTarget.BILIBILI;
    }

    @Override
    public UploadResult upload(UploadContext ctx) {
        if (ctx.video() == null) {
            return UploadResult.fail("视频文件不存在");
        }
        String tid = configService.getString("FIXED_PARTITION_ID_BILIBILI", "");
        if (tid == null || tid.isBlank()) {
            tid = ctx.task().getSelectedPartitionId() != null
                    ? ctx.task().getSelectedPartitionId() : "21";
        }

        var result = uploader.upload(ctx.video(), ctx.cover(),
                ctx.task().displayTitle(), ctx.task().getDescriptionTranslated(),
                ctx.task().getTagsGenerated(), tid, ctx.cookieFile(),
                ctx.progress(), ctx.logger(), ctx.cancelFlag());

        if (!result.success()) {
            return UploadResult.fail(result.error());
        }
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("bvid", result.bvid() == null ? "" : result.bvid());
        response.put("aid", result.aid() == null ? "" : result.aid());
        response.put("title", ctx.task().displayTitle() == null ? "" : ctx.task().displayTitle());
        response.put("url", "https://www.bilibili.com/video/" + (result.bvid() == null ? "" : result.bvid()));
        return UploadResult.ok("bilibili 投稿成功 " + result.bvid(), response);
    }
}

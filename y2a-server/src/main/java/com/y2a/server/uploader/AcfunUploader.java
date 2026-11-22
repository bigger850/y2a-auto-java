package com.y2a.server.uploader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.y2a.server.tools.PlatformHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AcFun 上传器（对齐 Python modules/acfun_uploader.py 协议）。
 *
 * <p>流程：取上传 token → 分片上传 → complete → uploadFinish → createVideo →
 * 上传封面 → createDouga 创建投稿。</p>
 */
@Component
public class AcfunUploader {

    private static final Logger log = LoggerFactory.getLogger(AcfunUploader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TOKEN_URL = "https://member.acfun.cn/video/api/getKSCloudToken";
    private static final String FRAGMENT_URL = "https://upload.kuaishouzt.com/api/upload/fragment";
    private static final String COMPLETE_URL = "https://upload.kuaishouzt.com/api/upload/complete";
    private static final String FINISH_URL = "https://member.acfun.cn/video/api/uploadFinish";
    private static final String CREATE_VIDEO_URL = "https://member.acfun.cn/video/api/createVideo";
    private static final String QINIU_URL = "https://member.acfun.cn/common/api/getQiniuToken";
    private static final String COVER_URL = "https://member.acfun.cn/common/api/getUrlAfterUpload";
    private static final String CREATE_DOUGA_URL = "https://member.acfun.cn/video/api/createDouga";
    private static final String CHANNELS_URL = "https://member.acfun.cn/video/api/getMyChannels";

    private static final Map<String, String> HEADERS = Map.of(
            "Origin", "https://member.acfun.cn",
            "Referer", "https://member.acfun.cn/upload-video"
    );

    private final PlatformHttpClient http;

    public AcfunUploader(PlatformHttpClient http) {
        this.http = http;
    }

    /** 上传结果。 */
    public record Result(boolean success, String acNumber, String coverUrl, String error) {
        public static Result ok(String ac, String cover) {
            return new Result(true, ac, cover, null);
        }

        public static Result fail(String error) {
            return new Result(false, null, null, error);
        }
    }

    /**
     * 完整上传流程。
     *
     * @param video      视频文件
     * @param cover      封面（可为 null）
     * @param title      标题
     * @param description 简介
     * @param tags       标签
     * @param partitionId AcFun 分区 ID
     * @param cookieFile Cookie 文件路径
     * @param progress   进度回调 0-100
     * @param logger     日志回调
     * @param cancelFlag 取消标记
     */
    public Result upload(Path video, Path cover, String title, String description,
                         List<String> tags, String partitionId, Path cookieFile,
                         Consumer<Double> progress, Consumer<String> logger,
                         AtomicBoolean cancelFlag) {
        try {
            Map<String, String> cookies = http.loadCookies(cookieFile);
            if (cookies.isEmpty()) {
                return Result.fail("AcFun Cookie 为空，请在设置页扫码登录");
            }

            // 1) 校验登录态
            var chResp = http.postForm(CHANNELS_URL, Map.of(), cookies, HEADERS);
            if (!chResp.ok()) {
                return Result.fail("AcFun 登录态无效（getMyChannels HTTP " + chResp.status() + "）");
            }

            String fileSize = String.valueOf(Files.size(video));
            String fileName = video.getFileName().toString();

            // 2) 取上传 token
            log(logger, "获取 AcFun 上传 token");
            var tokenResp = http.postForm(TOKEN_URL,
                    Map.of("fileName", fileName, "size", fileSize, "template", "1"),
                    cookies, HEADERS);
            if (!tokenResp.ok()) {
                return Result.fail("获取上传 token 失败: " + tokenResp.body());
            }
            JsonNode tokenJson = tokenResp.json();
            String taskId = tokenJson.path("taskId").asText("");
            String token = tokenJson.path("token").asText("");
            int partSize = tokenJson.path("uploadConfig").path("partSize").asInt(0);
            if (taskId.isEmpty() || token.isEmpty() || partSize <= 0) {
                return Result.fail("上传 token 响应异常: " + tokenResp.body());
            }

            // 3) 分片上传
            long size = Long.parseLong(fileSize);
            int fragmentCount = (int) Math.ceil((double) size / partSize);
            log(logger, String.format("开始上传 %s，共 %d 个分片", fileName, fragmentCount));

            try (var in = Files.newInputStream(video)) {
                for (int i = 0; i < fragmentCount; i++) {
                    if (cancelFlag != null && cancelFlag.get()) {
                        return Result.fail("任务已取消");
                    }
                    byte[] chunk = in.readNBytes(partSize);
                    if (chunk.length == 0) break;

                    boolean chunkOk = uploadChunkWithRetry(chunk, i, token, cookies, logger);
                    if (!chunkOk) {
                        return Result.fail("分片 " + (i + 1) + " 上传失败");
                    }
                    if (progress != null) {
                        progress.accept((i + 1) * 100.0 / fragmentCount);
                    }
                }
            }

            // 4) 完成上传
            var completeResp = http.postForm(
                    COMPLETE_URL + "?fragment_count=" + fragmentCount
                            + "&upload_token=" + urlEncode(token),
                    Map.of(), cookies, HEADERS);
            if (!completeResp.ok() || completeResp.json().path("result").asInt(-1) != 1) {
                log(logger, "完成上传确认异常（继续）: " + completeResp.body());
            }

            // 5) uploadFinish
            log(logger, "提交 uploadFinish");
            var finishResp = http.postForm(FINISH_URL, Map.of("taskId", taskId), cookies, HEADERS);
            if (!finishResp.ok() || finishResp.json().path("result").asInt(-1) != 0) {
                return Result.fail("uploadFinish 失败: " + finishResp.body());
            }

            // 6) createVideo
            log(logger, "创建视频记录");
            var videoResp = http.postForm(CREATE_VIDEO_URL,
                    Map.of("filename", fileName, "taskId", taskId), cookies, HEADERS);
            JsonNode videoJson = videoResp.json();
            int videoId = videoJson.path("videoId").asInt(0);
            if (videoId == 0) {
                return Result.fail("createVideo 失败: " + videoResp.body());
            }

            // 7) 上传封面
            String coverUrl = null;
            if (cover != null && Files.isRegularFile(cover)) {
                log(logger, "上传封面");
                coverUrl = uploadCover(cover, cookies, logger);
            }

            // 8) createDouga 创建投稿
            log(logger, "创建投稿");
            Map<String, String> dougaForm = new LinkedHashMap<>();
            dougaForm.put("title", truncate(title, 50));
            dougaForm.put("description", truncate(description, 1000));
            dougaForm.put("tagNames", MAPPER.writeValueAsString(
                    tags == null ? List.of() : tags.stream().limit(6).toList()));
            dougaForm.put("creationType", "3");
            dougaForm.put("channelId", partitionId == null ? "1" : partitionId);
            if (coverUrl != null) dougaForm.put("coverUrl", coverUrl);
            dougaForm.put("videoInfos", MAPPER.writeValueAsString(
                    List.of(Map.of("videoId", videoId, "title", truncate(title, 50)))));
            dougaForm.put("isJoinUpCollege", "0");
            dougaForm.put("isSyncKs", "0");
            dougaForm.put("originalDeclare", "1");

            var dougaResp = http.postForm(CREATE_DOUGA_URL, dougaForm, cookies, HEADERS);
            JsonNode dougaJson = dougaResp.json();
            if (dougaJson.path("result").asInt(-1) == 0 && dougaJson.has("dougaId")) {
                String ac = dougaJson.path("dougaId").asText();
                log(logger, "AcFun 投稿成功，AC" + ac);
                return Result.ok(ac, coverUrl);
            }
            String errMsg = dougaJson.path("errMsg").path("error_msg").asText(
                    dougaJson.path("error_msg").asText(dougaJson.path("msg").asText("未知错误")));
            return Result.fail("创建投稿失败: " + errMsg);

        } catch (Exception e) {
            log.error("AcFun 上传异常", e);
            return Result.fail("AcFun 上传异常: " + e.getMessage());
        }
    }

    private boolean uploadChunkWithRetry(byte[] chunk, int index, String token,
                                         Map<String, String> cookies, Consumer<String> logger) {
        String url = FRAGMENT_URL + "?fragment_id=" + index + "&upload_token=" + urlEncode(token);
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(1000L * (1L << attempt));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            var resp = http.postBytes(url, chunk, cookies, Map.of(), "application/octet-stream");
            if (resp.ok() && resp.json().path("result").asInt(-1) == 1) {
                return true;
            }
            log(logger, "分片 " + (index + 1) + " 第 " + (attempt + 1) + " 次失败: "
                    + (resp.ok() ? resp.body() : ("HTTP " + resp.status())));
        }
        return false;
    }

    private String uploadCover(Path cover, Map<String, String> cookies, Consumer<String> logger) {
        try {
            String ext = ".jpg";
            String name = cover.getFileName().toString().toLowerCase();
            if (name.endsWith(".png")) ext = ".png";
            else if (name.endsWith(".webp")) ext = ".webp";
            String randomName = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ext;

            var tokenResp = http.postForm(QINIU_URL, Map.of("fileName", randomName), cookies, HEADERS);
            String qiniuToken = tokenResp.json().path("info").path("token").asText("");
            if (qiniuToken.isEmpty()) {
                log(logger, "获取七牛 token 失败: " + tokenResp.body());
                return null;
            }

            byte[] data = Files.readAllBytes(cover);
            var chunkResp = http.postBytes(
                    FRAGMENT_URL + "?fragment_id=0&upload_token=" + urlEncode(qiniuToken),
                    data, cookies, Map.of(), "application/octet-stream");
            if (!chunkResp.ok() || chunkResp.json().path("result").asInt(-1) != 1) {
                log(logger, "封面上传失败: " + chunkResp.body());
                return null;
            }
            http.postForm(COMPLETE_URL + "?fragment_count=1&upload_token=" + urlEncode(qiniuToken),
                    Map.of(), cookies, HEADERS);

            var urlResp = http.postForm(COVER_URL,
                    Map.of("bizFlag", "web-douga-cover", "token", qiniuToken), cookies, HEADERS);
            return urlResp.json().path("url").asText(null);
        } catch (Exception e) {
            log(logger, "封面上传异常（继续）: " + e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.length() <= max) return t;
        return max <= 3 ? t.substring(0, max) : t.substring(0, max - 3) + "...";
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void log(Consumer<String> logger, String message) {
        if (logger != null) logger.accept(message);
        log.info(message);
    }
}

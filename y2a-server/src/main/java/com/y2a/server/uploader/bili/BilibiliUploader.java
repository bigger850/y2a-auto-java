package com.y2a.server.uploader.bili;

import com.fasterxml.jackson.databind.JsonNode;
import com.y2a.server.tools.PlatformHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * bilibili 上传器（UPOS 预上传 + 分片 + web/add/v3 投稿）。
 *
 * <p>对齐 Python bili_sdk 的关键流程，去掉多通道回退与并发调优，保留可用的最小闭环：
 * nav（取 WBI key + 登录态）→ preupload → 分片 PUT → 封面 → web/add/v3。</p>
 */
@Component
public class BilibiliUploader {

    private static final Logger log = LoggerFactory.getLogger(BilibiliUploader.class);

    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final String PREUPLOAD_URL = "https://member.bilibili.com/preupload";
    private static final String ADD_URL = "https://member.bilibili.com/x/vu/web/add/v3";
    private static final String COVER_URL = "https://member.bilibili.com/x/vu/web/cover/up";

    private final PlatformHttpClient http;

    public BilibiliUploader(PlatformHttpClient http) {
        this.http = http;
    }

    /** 上传结果。 */
    public record Result(boolean success, String bvid, String aid, String error) {
        public static Result ok(String bvid, String aid) {
            return new Result(true, bvid, aid, null);
        }

        public static Result fail(String error) {
            return new Result(false, null, null, error);
        }
    }

    /** 完整投稿流程。 */
    public Result upload(Path video, Path cover, String title, String description,
                         List<String> tags, String tid, Path cookieFile,
                         Consumer<Double> progress, Consumer<String> logger,
                         AtomicBoolean cancelFlag) {
        try {
            Map<String, String> cookies = http.loadCookies(cookieFile);
            if (cookies.isEmpty()) {
                return Result.fail("bilibili Cookie 为空，请在设置页扫码登录");
            }
            Map<String, String> headers = Map.of("Referer", "https://member.bilibili.com/");

            // 1) nav：校验登录 + 取 WBI 密钥
            var navResp = http.get(NAV_URL, cookies, headers);
            JsonNode nav = WbiSigner.parse(navResp.body());
            if (nav.path("code").asInt(-1) != 0 || !nav.path("data").path("isLogin").asBoolean(false)) {
                return Result.fail("bilibili 登录态无效: " + nav.path("message").asText("未登录"));
            }
            String mixinKey = WbiSigner.mixinKeyFromNav(nav);

            // 2) preupload
            log(logger, "bilibili 预上传初始化");
            String fileName = video.getFileName().toString();
            long size = Files.size(video);
            String preUrl = PREUPLOAD_URL + "?profile=ugcfx/bup&name=" + enc(fileName)
                    + "&size=" + size + "&r=upos&ssl=0&version=2.14.0&build=2100400";
            var preResp = http.get(preUrl, cookies, headers);
            JsonNode pre = WbiSigner.parse(preResp.body());
            if (pre.path("OK").asInt(0) != 1) {
                return Result.fail("预上传失败: " + preResp.body());
            }
            String uploadUrl = pre.path("endpoint").asText("")
                    + pre.path("upos_uri").asText("").replace("upos://", "/");
            if (!uploadUrl.startsWith("http")) {
                uploadUrl = "https:" + uploadUrl;
            }
            String auth = pre.path("auth").asText("");
            int chunkSize = pre.path("chunk_size").asInt(1 << 20);
            String bizId = pre.path("biz_id").asText("");

            // 3) 初始化分片会话
            String initUrl = uploadUrl + "?uploads&output=json&profile=ugcfx/bup"
                    + "&filesize=" + size + "&partsize=" + chunkSize + "&biz_id=" + bizId;
            var initResp = http.postBytes(initUrl, new byte[0], cookies,
                    Map.of("x-upos-auth", auth), "application/octet-stream");
            JsonNode init = WbiSigner.parse(initResp.body());
            String uploadId = init.path("upload_id").asText("");
            if (uploadId.isEmpty()) {
                return Result.fail("初始化分片失败: " + initResp.body());
            }

            // 4) 分片上传
            int parts = (int) Math.ceil((double) size / chunkSize);
            log(logger, String.format("bilibili 上传 %s，共 %d 个分片", fileName, parts));
            try (var in = Files.newInputStream(video)) {
                for (int i = 0; i < parts; i++) {
                    if (cancelFlag != null && cancelFlag.get()) {
                        return Result.fail("任务已取消");
                    }
                    byte[] chunk = in.readNBytes(chunkSize);
                    if (chunk.length == 0) break;
                    String partUrl = uploadUrl + "?partNumber=" + (i + 1)
                            + "&uploadId=" + enc(uploadId)
                            + "&chunk=" + i + "&chunks=" + parts
                            + "&size=" + chunk.length + "&start=" + ((long) i * chunkSize)
                            + "&end=" + ((long) i * chunkSize + chunk.length)
                            + "&total=" + size;
                    var partResp = http.postBytes(partUrl, chunk, cookies,
                            Map.of("x-upos-auth", auth), "application/octet-stream");
                    if (!partResp.ok()) {
                        return Result.fail("分片 " + (i + 1) + " 上传失败: HTTP " + partResp.status());
                    }
                    if (progress != null) progress.accept((i + 1) * 90.0 / parts);
                }
            }

            // 5) 结束分片会话
            String doneUrl = uploadUrl + "?output=json&name=" + enc(fileName)
                    + "&profile=ugcfx/bup&uploadId=" + enc(uploadId) + "&biz_id=" + bizId;
            var doneResp = http.postBytes(doneUrl, new byte[0], cookies,
                    Map.of("x-upos-auth", auth), "application/octet-stream");
            JsonNode done = WbiSigner.parse(doneResp.body());
            if (!"0".equals(done.path("OK").asText(done.path("OK").asInt(-1) == 0 ? "0" : "1"))) {
                log(logger, "分片结束响应: " + doneResp.body());
            }

            // 6) 封面（可选）
            String coverUrl = null;
            if (cover != null && Files.isRegularFile(cover)) {
                log(logger, "上传 bilibili 封面");
                coverUrl = uploadCover(cover, cookies, headers);
            }

            // 7) 投稿
            log(logger, "提交 bilibili 投稿");
            Map<String, String> params = new LinkedHashMap<>();
            params.put("copyright", "1");
            params.put("source", "");
            params.put("title", trunc(title, 80));
            params.put("tid", tid == null || tid.isBlank() ? "21" : tid);
            params.put("desc_format_id", "0");
            params.put("desc", trunc(description, 2000));
            params.put("tag", tags == null ? "" : String.join(",", tags.stream().limit(12).toList()));
            params.put("videos", "1");
            params.put("cover", coverUrl == null ? "" : coverUrl);
            params.put("channel_id", "0");
            params.put("no_reprint", "0");
            Map<String, String> signed = mixinKey.isEmpty()
                    ? params : WbiSigner.sign(params, mixinKey);
            // 视频分 P 信息
            signed.put("uid", nav.path("data").path("mid").asText(""));
            signed.put("bvid", "");

            String body = buildAddBody(uploadUrl, fileName, size, signed);
            var addResp = http.postJson(ADD_URL + "?" + toQuery(signed), body, cookies,
                    Map.of("Referer", "https://member.bilibili.com/platform/upload/video/frame"));
            JsonNode add = WbiSigner.parse(addResp.body());
            if (add.path("code").asInt(-1) == 0) {
                JsonNode data = add.path("data");
                String bvid = data.path("bvid").asText("");
                String aid = data.path("aid").asText("");
                log(logger, "bilibili 投稿成功: " + bvid);
                if (progress != null) progress.accept(100.0);
                return Result.ok(bvid, aid);
            }
            return Result.fail("投稿失败: " + add.path("message").asText(addResp.body()));

        } catch (Exception e) {
            log.error("bilibili 上传异常", e);
            return Result.fail("bilibili 上传异常: " + e.getMessage());
        }
    }

    private String uploadCover(Path cover, Map<String, String> cookies, Map<String, String> headers) {
        try {
            String base64 = java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(cover));
            Map<String, String> form = Map.of("cover", "data:image/jpeg;base64," + base64);
            var resp = http.postForm(COVER_URL, form, cookies, headers);
            JsonNode json = WbiSigner.parse(resp.body());
            if (json.path("code").asInt(-1) == 0) {
                return json.path("data").path("url").asText(null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildAddBody(String uposUri, String fileName, long size, Map<String, String> signed) {
        String uri = uposUri.replaceFirst("^https://[^/]+", "");
        return """
                {"videos":[{"filename":"%s","title":"%s","desc":"","cid":0}]}
                """.formatted(esc(fileName), esc(fileName));
    }

    private static String toQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(k)).append('=').append(enc(v));
        });
        return sb.toString();
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void log(Consumer<String> logger, String message) {
        if (logger != null) logger.accept(message);
        log.info(message);
    }
}

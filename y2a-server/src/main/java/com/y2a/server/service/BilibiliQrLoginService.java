package com.y2a.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.y2a.server.tools.PlatformHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * bilibili 扫码登录（纯 HTTP 协议，对齐 Python bili_sdk/login_v2.py）。
 *
 * <p>流程：
 * <ol>
 *   <li>GET /x/passport-login/web/qrcode/generate → {url, qrcode_key}</li>
 *   <li>本地用 ZXing 把 url 渲染成二维码 PNG（原版用 qrcode 库）</li>
 *   <li>GET /x/passport-login/web/qrcode/poll?qrcode_key=... 轮询：
 *       86101 待扫码 / 86090 已扫码待确认 / 86038 已过期 / 0 成功</li>
 *   <li>成功时从响应 url 的 query 与 Set-Cookie 提取 SESSDATA / bili_jct /
 *       DedeUserID / buvid3 / buvid4 / ac_time_value，写入 Cookie JSON</li>
 * </ol>
 * </p>
 */
@Service
public class BilibiliQrLoginService {

    private static final Logger log = LoggerFactory.getLogger(BilibiliQrLoginService.class);

    private static final String GENERATE_URL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header";
    private static final String POLL_URL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";
    private static final Map<String, String> HEADERS = Map.of(
            "Referer", "https://www.bilibili.com/",
            "Origin", "https://www.bilibili.com"
    );

    private final PlatformHttpClient http;
    private final QrSessionService qrSessions;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.storage.cookies:./storage/cookies}")
    private String cookiesDir;

    /** sessionId → 扫码上下文。 */
    private final Map<String, Ctx> contexts = new ConcurrentHashMap<>();

    public BilibiliQrLoginService(PlatformHttpClient http, QrSessionService qrSessions) {
        this.http = http;
        this.qrSessions = qrSessions;
    }

    private static final class Ctx {
        String qrKey;
        String phase = "scan";   // scan → confirm → done
        /** 轮询过程中服务端下发的 Cookie（用于最终拼装凭证）。 */
        final Map<String, String> cookies = new LinkedHashMap<>();
    }

    /** 生成二维码，返回 base64 PNG。 */
    public Map<String, Object> start(QrSessionService.QrSession session) {
        var resp = http.get(GENERATE_URL, Map.of(), HEADERS);
        if (resp.status() <= 0) {
            throw new IllegalStateException("请求 bilibili 二维码失败: " + resp.body());
        }
        JsonNode json = parse(resp.body());
        if (json.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("bilibili 二维码接口返回错误: "
                    + json.path("message").asText(json.path("msg").asText("unknown")));
        }
        JsonNode data = json.path("data");
        String qrUrl = data.path("url").asText("");
        String qrKey = data.path("qrcode_key").asText("");
        if (qrUrl.isEmpty() || qrKey.isEmpty()) {
            throw new IllegalStateException("bilibili 二维码响应字段不完整");
        }

        Ctx ctx = new Ctx();
        ctx.qrKey = qrKey;
        // 初次请求可能已下发 buvid3 等，先收集
        collectCookies(ctx, resp.headers().getOrDefault("set-cookie", ""));
        contexts.put(session.id, ctx);
        session.cookiePath = Paths.get(cookiesDir).resolve("bili_cookies.json").toString();

        String base64 = renderQrPng(qrUrl);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("image_base64", base64);
        out.put("mime_type", "image/png");
        out.put("qr_url", qrUrl);
        return out;
    }

    /** 轮询扫码状态。 */
    public void poll(QrSessionService.QrSession session) {
        Ctx ctx = contexts.get(session.id);
        if (ctx == null || "done".equals(ctx.phase)) return;
        try {
            String url = POLL_URL + "?qrcode_key=" + enc(ctx.qrKey) + "&source=main-fe-header";
            var resp = http.get(url, ctx.cookies, HEADERS, java.time.Duration.ofSeconds(10));
            if (resp.status() <= 0) {
                // 网络抖动/超时：保持当前状态
                return;
            }
            JsonNode json = parse(resp.body());
            if (json.path("code").asInt(-1) != 0) {
                session.message = json.path("message").asText("轮询失败");
                return;
            }
            JsonNode data = json.path("data");
            int code = data.path("code").asInt(-1);

            if (code == 86101) {
                session.status = "wait_scan";
                session.message = "等待扫码";
                return;
            }
            if (code == 86090) {
                ctx.phase = "confirm";
                session.status = "scan";
                session.message = "已扫码，请在手机端确认";
                return;
            }
            if (code == 86038) {
                ctx.phase = "timeout";
                session.status = "expired";
                session.message = "二维码已过期，请重新获取";
                return;
            }
            if (code == 0) {
                finishLogin(session, ctx, resp);
                return;
            }
            session.message = "未知扫码状态 code=" + code;
        } catch (Exception e) {
            log.warn("bilibili 扫码轮询异常", e);
        }
    }

    private void finishLogin(QrSessionService.QrSession session, Ctx ctx,
                             PlatformHttpClient.Response resp) {
        // 1) 从响应 url 的 query 提取凭证
        String redirectUrl = parse(resp.body()).path("data").path("url").asText("");
        Map<String, String> fromUrl = parseQuery(redirectUrl);

        // 2) 合并 Set-Cookie
        collectCookies(ctx, resp.headers().getOrDefault("set-cookie", ""));

        Map<String, String> merged = new LinkedHashMap<>();
        for (String key : new String[]{"SESSDATA", "bili_jct", "DedeUserID",
                "buvid3", "buvid4", "_uuid"}) {
            String v = firstNonBlank(ctx.cookies.get(key), fromUrl.get(key));
            if (v != null && !v.isBlank()) merged.put(key, v);
        }
        String acTime = firstNonBlank(fromUrl.get("refresh_token"), ctx.cookies.get("ac_time_value"));

        if (!merged.containsKey("SESSDATA") || !merged.containsKey("bili_jct")
                || !merged.containsKey("DedeUserID")) {
            ctx.phase = "failed";
            session.status = "failed";
            session.message = "登录成功但未获取到完整 Cookie，请重试";
            return;
        }
        if (acTime != null && !acTime.isBlank()) {
            merged.put("ac_time_value", acTime);
        }

        // 3) 落盘
        boolean saved = writeCookies(session.cookiePath, merged);
        ctx.phase = "done";
        session.status = saved ? "success" : "failed";
        session.message = saved
                ? "bilibili 登录成功，Cookies 已保存"
                : "登录成功但 Cookies 保存失败";
        if (saved) {
            log.info("bilibili 登录态已保存: {} ({} 项)", session.cookiePath, merged.size());
        }
    }

    // ---------------- 二维码渲染 ----------------

    /**
     * 用 ZXing 把登录 URL 渲染为 PNG base64。
     * 原版用 Python qrcode 库；ZXing 是同标准的 Java 实现。
     */
    private String renderQrPng(String content) {
        try {
            var hints = new java.util.EnumMap<com.google.zxing.EncodeHintType, Object>(
                    com.google.zxing.EncodeHintType.class);
            hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION,
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);

            var matrix = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 320, 320, hints);

            var image = new java.awt.image.BufferedImage(
                    matrix.getWidth(), matrix.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < matrix.getWidth(); x++) {
                for (int y = 0; y < matrix.getHeight(); y++) {
                    image.setRGB(x, y, matrix.get(x, y)
                            ? java.awt.Color.BLACK.getRGB() : java.awt.Color.WHITE.getRGB());
                }
            }
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "PNG", out);
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("生成二维码图片失败: " + e.getMessage(), e);
        }
    }

    // ---------------- 工具 ----------------

    private static void collectCookies(Ctx ctx, String setCookieHeader) {
        if (setCookieHeader == null || setCookieHeader.isBlank()) return;
        for (String part : setCookieHeader.split(",")) {
            String pair = part.split(";")[0].trim();
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String name = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                if (!name.isEmpty() && !value.isEmpty()) {
                    ctx.cookies.put(name, value);
                }
            }
        }
    }

    private boolean writeCookies(String cookiePath, Map<String, String> cookies) {
        if (cookiePath == null) return false;
        try {
            Path path = Paths.get(cookiePath);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            // 保持与 Python 版一致的键顺序
            Map<String, String> ordered = new LinkedHashMap<>();
            for (String key : new String[]{"SESSDATA", "bili_jct", "DedeUserID",
                    "buvid3", "buvid4", "_uuid", "ac_time_value"}) {
                if (cookies.containsKey(key)) ordered.put(key, cookies.get(key));
            }
            cookies.forEach(ordered::putIfAbsent);
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), ordered);
            return true;
        } catch (Exception e) {
            log.error("写入 bilibili Cookie 失败", e);
            return false;
        }
    }

    private static Map<String, String> parseQuery(String url) {
        Map<String, String> result = new LinkedHashMap<>();
        if (url == null || url.isEmpty()) return result;
        int q = url.indexOf('?');
        if (q < 0) return result;
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                result.put(k, v);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static JsonNode parse(String body) {
        try {
            return new ObjectMapper().readTree(body);
        } catch (Exception e) {
            return new ObjectMapper().createObjectNode();
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}

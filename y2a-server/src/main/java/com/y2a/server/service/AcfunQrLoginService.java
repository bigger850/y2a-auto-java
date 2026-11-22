package com.y2a.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.y2a.server.tools.PlatformHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AcFun 扫码登录（纯 HTTP，对齐 Python modules/acfun_auth.py）。
 *
 * <p>start → scanResult（轮询扫码）→ acceptResult（轮询确认）→ 保存 Cookie JSON。</p>
 */
@Service
public class AcfunQrLoginService {

    private static final Logger log = LoggerFactory.getLogger(AcfunQrLoginService.class);

    private static final String START_URL = "https://scan.acfun.cn/rest/pc-direct/qr/start";
    private static final String SCAN_URL = "https://scan.acfun.cn/rest/pc-direct/qr/scanResult";
    private static final String ACCEPT_URL = "https://scan.acfun.cn/rest/pc-direct/qr/acceptResult";
    private static final Map<String, String> HEADERS = Map.of(
            "Origin", "https://www.acfun.cn",
            "Referer", "https://www.acfun.cn/login"
    );

    private final PlatformHttpClient http;
    private final QrSessionService qrSessions;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.storage.cookies:./storage/cookies}")
    private String cookiesDir;

    /** sessionId → 扫码上下文。 */
    private final Map<String, Ctx> contexts = new ConcurrentHashMap<>();

    public AcfunQrLoginService(PlatformHttpClient http, QrSessionService qrSessions) {
        this.http = http;
        this.qrSessions = qrSessions;
    }

    private static final class Ctx {
        String token;
        String signature;
        String phase = "scan";
    }

    /** 生成二维码（返回 base64 图片）。 */
    public Map<String, Object> start(QrSessionService.QrSession session) {
        var resp = http.get(START_URL + "?type=WEB_LOGIN", Map.of(), HEADERS);
        JsonNode json = parse(resp.body());
        int code = resultCode(json);
        if (code != 0) {
            throw new IllegalStateException("获取 AcFun 二维码失败: "
                    + json.path("error_msg").asText("code=" + code));
        }
        String image = json.path("imageData").asText("");
        String token = json.path("qrLoginToken").asText("");
        String signature = json.path("qrLoginSignature").asText("");
        if (image.isEmpty() || token.isEmpty() || signature.isEmpty()) {
            throw new IllegalStateException("获取 AcFun 二维码失败: 返回字段不完整");
        }
        Ctx ctx = new Ctx();
        ctx.token = token;
        ctx.signature = signature;
        contexts.put(session.id, ctx);
        session.cookiePath = Paths.get(cookiesDir).resolve("ac_cookies.json").toString();

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("image_base64", image);
        data.put("mime_type", "image/png");
        data.put("expires_in_ms", json.path("expireTime").asInt(120000));
        return data;
    }

    /** 轮询状态（scan → accept → done）。 */
    public void poll(QrSessionService.QrSession session) {
        Ctx ctx = contexts.get(session.id);
        if (ctx == null) return;
        try {
            if ("scan".equals(ctx.phase)) {
                pollScan(session, ctx);
            }
            if ("accept".equals(ctx.phase)) {
                pollAccept(session, ctx);
            }
        } catch (Exception e) {
            session.status = "failed";
            session.message = "查询扫码状态失败: " + e.getMessage();
        }
    }

    private void pollScan(QrSessionService.QrSession session, Ctx ctx) {
        String url = SCAN_URL + "?qrLoginToken=" + enc(ctx.token)
                + "&qrLoginSignature=" + enc(ctx.signature);
        var resp = http.get(url, Map.of(), HEADERS, java.time.Duration.ofSeconds(8));

        // AcFun 的 scanResult 是长轮询：未扫码时服务端挂起，客户端读超时即为「等待扫码」。
        // 与 Python 版把 ReadTimeout 当作 wait_scan 的处理一致。
        if (resp.status() <= 0) {
            session.status = "wait_scan";
            session.message = "等待扫码";
            return;
        }
        JsonNode json = parse(resp.body());
        int code = resultCode(json);

        if (code == 0) {
            String next = json.path("qrLoginSignature").asText("").trim();
            if (!next.isEmpty()) ctx.signature = next;
            ctx.phase = "accept";
            session.status = "scan";
            session.message = "已扫码，等待手机确认";
            pollAccept(session, ctx);
            return;
        }
        if (code == 100400002) {
            ctx.phase = "timeout";
            session.status = "expired";
            session.message = "二维码已过期，请重新获取";
            return;
        }
        // 其它非零码：仍按等待扫码处理（协议偶发业务错误不应终结会话）
        session.status = "wait_scan";
        session.message = "等待扫码";
    }

    private void pollAccept(QrSessionService.QrSession session, Ctx ctx) {
        String url = ACCEPT_URL + "?qrLoginToken=" + enc(ctx.token)
                + "&qrLoginSignature=" + enc(ctx.signature);
        var resp = http.get(url, Map.of(), HEADERS, java.time.Duration.ofSeconds(8));
        if (resp.status() <= 0) {
            session.status = "confirm";
            session.message = "等待手机端确认登录";
            return;
        }
        JsonNode json = parse(resp.body());
        int code = resultCode(json);

        if (code == 0) {
            // 从响应头 Set-Cookie 提取登录态并落盘
            String setCookie = resp.headers().getOrDefault("set-cookie", "");
            boolean saved = saveCookies(session.cookiePath, setCookie);
            ctx.phase = "done";
            session.status = "success";
            session.message = saved ? "登录成功，Cookies 已保存"
                    : "登录成功，但未获取到有效 Cookie，请重试";
            if (!saved) session.status = "failed";
            return;
        }
        if (code == 100400002) {
            ctx.phase = "timeout";
            session.status = "expired";
            session.message = "二维码已过期，请重新获取";
            return;
        }
        session.status = "confirm";
        session.message = "等待手机端确认登录";
    }

    private boolean saveCookies(String cookiePath, String setCookieHeader) {
        if (cookiePath == null || setCookieHeader == null || setCookieHeader.isBlank()) {
            return false;
        }
        try {
            Path path = Paths.get(cookiePath);
            Files.createDirectories(path.getParent());
            Map<String, String> cookies = new java.util.LinkedHashMap<>();
            for (String part : setCookieHeader.split(",")) {
                String pair = part.split(";")[0].trim();
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            if (cookies.isEmpty()) return false;
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), cookies);
            log.info("AcFun 登录态已保存: {} ({} 项)", path, cookies.size());
            return true;
        } catch (Exception e) {
            log.warn("保存 AcFun Cookie 失败", e);
            return false;
        }
    }

    private static JsonNode parse(String body) {
        try {
            return new ObjectMapper().readTree(body);
        } catch (Exception e) {
            return new ObjectMapper().createObjectNode();
        }
    }

    /** AcFun 的 result 可能是数字或对象嵌套。 */
    private static int resultCode(JsonNode json) {
        JsonNode r = json.path("result");
        if (r.isNumber()) return r.asInt();
        if (r.isObject() && r.path("result").isNumber()) return r.path("result").asInt();
        return -1;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }
}

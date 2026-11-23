package com.y2a.server.controller;

import com.y2a.server.common.exception.ApiResponse;
import com.y2a.server.common.exception.BizException;
import com.y2a.server.service.DouyinQrLoginService;
import com.y2a.server.service.QrSessionService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 扫码登录端点（AcFun / bilibili / 抖音）。
 *
 * <p>抖音走 Playwright 真实截图 + 轮询登录；AcFun/bilibili 目前返回会话并提示
 * 需要 Cookie 导入（协议版扫码待接）。</p>
 */
@RestController
@RequestMapping("/api/settings")
public class QrLoginController {

    private static final Set<String> SUPPORTED = Set.of("acfun", "bilibili", "douyin");

    private final QrSessionService qrSessionService;
    private final DouyinQrLoginService douyinQrLogin;
    private final com.y2a.server.service.AcfunQrLoginService acfunQrLogin;
    private final com.y2a.server.service.BilibiliQrLoginService bilibiliQrLogin;
    private final com.y2a.server.service.ResourceTicketService ticketService;

    public QrLoginController(QrSessionService qrSessionService,
                             DouyinQrLoginService douyinQrLogin,
                             com.y2a.server.service.AcfunQrLoginService acfunQrLogin,
                             com.y2a.server.service.BilibiliQrLoginService bilibiliQrLogin,
                             com.y2a.server.service.ResourceTicketService ticketService) {
        this.qrSessionService = qrSessionService;
        this.douyinQrLogin = douyinQrLogin;
        this.acfunQrLogin = acfunQrLogin;
        this.bilibiliQrLogin = bilibiliQrLogin;
        this.ticketService = ticketService;
    }

    @PostMapping("/{platform}/qrcode/start")
    public ApiResponse<Map<String, Object>> start(@PathVariable String platform) {
        requireSupported(platform);

        if ("douyin".equals(platform)) {
            if (!douyinQrLogin.available()) {
                throw BizException.badRequest(
                        "抖音扫码不可用：当前环境缺少 Playwright Chromium，请改用手动导入登录态文件");
            }
            QrSessionService.QrSession session = douyinQrLogin.start(platform);
            String ticket = ticketService.issue(session.id, "qr-image");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("session_id", session.id);
            data.put("expires_in", session.ttlMs / 1000);
            // 前端 <img src> 无法携带 Authorization 头，用一次性 ticket 代替
            data.put("image_url",
                    "/api/settings/douyin/qrcode/image/" + session.id + "?ticket=" + ticket);
            data.put("message", "正在生成二维码，请稍候...");
            return ApiResponse.ok(data);
        }

        QrSessionService.QrSession session = qrSessionService.create(platform);

        if ("acfun".equals(platform)) {
            try {
                Map<String, Object> qr = acfunQrLogin.start(session);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("session_id", session.id);
                data.put("expires_in", session.ttlMs / 1000);
                data.put("image_base64", qr.get("image_base64"));
                data.put("mime_type", qr.get("mime_type"));
                data.put("message", "二维码已生成，请用 AcFun App 扫码");
                return ApiResponse.ok(data);
            } catch (Exception e) {
                return ApiResponse.ok(Map.of(
                        "session_id", session.id,
                        "expires_in", session.ttlMs / 1000,
                        "message", "AcFun 二维码生成失败：" + e.getMessage()
                                + "；可改用手动导入 Cookies"));
            }
        }

        if ("bilibili".equals(platform)) {
            try {
                Map<String, Object> qr = bilibiliQrLogin.start(session);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("session_id", session.id);
                data.put("expires_in", session.ttlMs / 1000);
                data.put("image_base64", qr.get("image_base64"));
                data.put("mime_type", qr.get("mime_type"));
                data.put("message", "二维码已生成，请用 bilibili App 扫码");
                return ApiResponse.ok(data);
            } catch (Exception e) {
                return ApiResponse.ok(Map.of(
                        "session_id", session.id,
                        "expires_in", session.ttlMs / 1000,
                        "message", "bilibili 二维码生成失败：" + e.getMessage()
                                + "；可改用手动导入 Cookies"));
            }
        }

        // 兜底（正常不会走到）
        qrSessionService.update(session.id, "wait_scan", "该平台暂不支持扫码");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_id", session.id);
        data.put("expires_in", session.ttlMs / 1000);
        data.put("message", "该平台暂不支持扫码，请手动导入 Cookies");
        return ApiResponse.ok(data);
    }

    @GetMapping("/{platform}/qrcode/status/{sessionId}")
    public ApiResponse<Map<String, Object>> status(@PathVariable String platform,
                                                   @PathVariable String sessionId) {
        requireSupported(platform);
        QrSessionService.QrSession s = qrSessionService.get(sessionId);
        Map<String, Object> data = new LinkedHashMap<>();
        if (s == null) {
            data.put("success", false);
            data.put("status", "timeout");
            data.put("message", "会话已过期，请重新获取二维码");
            return ApiResponse.ok(data);
        }
        // AcFun / bilibili 走主动轮询（协议无服务端推送）
        if ("acfun".equals(platform)) {
            try {
                acfunQrLogin.poll(s);
            } catch (Exception ignored) {
            }
        } else if ("bilibili".equals(platform)) {
            try {
                bilibiliQrLogin.poll(s);
            } catch (Exception ignored) {
            }
        }
        boolean terminal = Set.of("success", "failed", "expired", "done", "timeout").contains(s.status);
        data.put("success", !"failed".equals(s.status));
        data.put("status", normalizeStatus(s.status));
        data.put("message", s.message);
        // 抖音：截图是否已落盘，前端据此决定何时设置 <img src>
        data.put("image_ready", s.qrImagePath != null && !"waiting".equals(s.status));
        if ("success".equals(s.status)) {
            data.put("cookies_saved", true);
        }
        if (terminal) {
            qrSessionService.remove(sessionId);
        }
        return ApiResponse.ok(data);
    }

    /**
     * 抖音二维码图片。
     *
     * <p>浏览器对 &lt;img src&gt; 不会附带 Authorization 头，因此本端点不走 JWT，
     * 改用 {@code ?ticket=xxx} 的一次性票据（由 start 接口签发，与 sessionId 绑定）。</p>
     */
    @GetMapping("/douyin/qrcode/image/{sessionId}")
    public ResponseEntity<ByteArrayResource> douyinImage(
            @PathVariable String sessionId,
            @RequestParam(value = "ticket", required = false) String ticket) {
        if (!ticketService.validate(ticket, sessionId)) {
            return ResponseEntity.status(401).build();
        }
        QrSessionService.QrSession s = qrSessionService.get(sessionId);
        if (s == null || s.qrImagePath == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            Path path = Path.of(s.qrImagePath);
            if (!Files.isRegularFile(path)) {
                return ResponseEntity.notFound().build();
            }
            byte[] bytes = Files.readAllBytes(path);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header("Cache-Control", "no-store")
                    .body(new ByteArrayResource(bytes));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 前端状态映射：waiting/ready → pending（继续轮询），success/expired → 终态。 */
    private static String normalizeStatus(String raw) {
        return switch (raw == null ? "" : raw) {
            case "waiting", "ready", "wait_scan", "scan", "confirm" -> "pending";
            case "success" -> "done";
            case "expired" -> "timeout";
            case "failed" -> "failed";
            default -> raw;
        };
    }

    private void requireSupported(String platform) {
        if (!SUPPORTED.contains(platform)) {
            throw BizException.badRequest("不支持的平台: " + platform);
        }
    }
}

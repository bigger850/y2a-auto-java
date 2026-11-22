package com.y2a.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 二维码登录会话管理（三平台共用）。
 *
 * <p>对齐原版：AcFun TTL 420s、bilibili/douyin 300s。实际扫码由
 * 「处理引擎」完成（Playwright / 平台协议），Java 侧持有会话状态并透传。</p>
 */
@Service
public class QrSessionService {

    private static final Logger log = LoggerFactory.getLogger(QrSessionService.class);

    private static final Map<String, String> TTL_BY_PLATFORM = Map.of(
            "acfun", "420", "bilibili", "300", "douyin", "300");

    /** sessionId → session。 */
    private final Map<String, QrSession> sessions = new ConcurrentHashMap<>();

    public QrSession create(String platform) {
        cleanup();
        String id = UUID.randomUUID().toString();
        int ttl = Integer.parseInt(TTL_BY_PLATFORM.getOrDefault(platform, "300"));
        QrSession s = new QrSession(id, platform, System.currentTimeMillis(), ttl * 1000L);
        sessions.put(id, s);
        log.info("创建 {} 扫码会话: {}", platform, id);
        return s;
    }

    public QrSession get(String sessionId) {
        cleanup();
        return sessions.get(sessionId);
    }

    public void update(String sessionId, String status, String message) {
        QrSession s = sessions.get(sessionId);
        if (s != null) {
            s.status = status;
            s.message = message;
        }
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public Map<String, Object> startResponse(QrSession s) {
        return Map.of(
                "session_id", s.id,
                "expires_in", s.ttlMs / 1000,
                "cookie_path", ""
        );
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().createdAt > e.getValue().ttlMs);
    }

    /** 扫码会话。 */
    public static final class QrSession {
        public final String id;
        public final String platform;
        public final long createdAt;
        public final long ttlMs;
        public volatile String status = "wait_scan";
        public volatile String message = "等待扫码";
        /** 二维码图片绝对路径（Playwright 截图落盘后写入）。 */
        public volatile String qrImagePath;
        /** 登录态保存目标文件。 */
        public volatile String cookiePath;
        /** 是否已发送过登录结果通知（避免重复推送）。 */
        public volatile boolean notified;
        /** 后台任务句柄（便于清理）。 */
        public volatile Thread worker;

        QrSession(String id, String platform, long createdAt, long ttlMs) {
            this.id = id;
            this.platform = platform;
            this.createdAt = createdAt;
            this.ttlMs = ttlMs;
        }
    }
}

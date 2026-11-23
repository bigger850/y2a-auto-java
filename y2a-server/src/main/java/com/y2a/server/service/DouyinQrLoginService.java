package com.y2a.server.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 抖音扫码登录（Playwright Java）。
 *
 * <p>流程：打开创作者平台 → 触发登录弹窗 → 截取二维码到 temp 目录 →
 * 轮询页面直到登录成功 → 保存 storage_state 到 cookies/douyin_cookies.json。</p>
 *
 * <p>对齐 Python douyin_uploader.start_qr_login 的状态语义：
 * waiting / success / failed / expired。</p>
 */
@Service
public class DouyinQrLoginService {

    private static final Logger log = LoggerFactory.getLogger(DouyinQrLoginService.class);

    private static final String UPLOAD_URL = "https://creator.douyin.com/creator-micro/content/upload";
    private static final String CREATOR_HOST = "creator.douyin.com";
    /** 等待二维码渲染的最长时间（抖音登录弹窗 + 二维码图生成）。 */
    private static final long QR_WAIT_TIMEOUT_MS = 60_000L;

    private final QrSessionService qrSessions;
    private final boolean enabled;

    @Value("${app.storage.cookies:./storage/cookies}")
    private String cookiesDir;

    @Value("${app.storage.temp:./storage/temp}")
    private String tempDir;

    public DouyinQrLoginService(QrSessionService qrSessions,
                                @Value("${y2a.playwright.enabled:true}") boolean enabled) {
        this.qrSessions = qrSessions;
        this.enabled = enabled;
    }

    /** 是否有 Playwright 可用（启停开关 + Chromium 存在）。 */
    public boolean available() {
        if (!enabled) return false;
        Path browsers = Paths.get(System.getenv().getOrDefault(
                "PLAYWRIGHT_BROWSERS_PATH", "/ms-playwright"));
        if (!Files.isDirectory(browsers)) return false;
        try (var entries = Files.list(browsers)) {
            return entries.anyMatch(p -> p.getFileName().toString().startsWith("chromium"));
        } catch (Exception e) {
            return false;
        }
    }

    /** 启动扫码会话（异步线程，立即返回）。 */
    public QrSessionService.QrSession start(String platform) {
        QrSessionService.QrSession session = qrSessions.create(platform);
        session.cookiePath = Paths.get(cookiesDir).resolve("douyin_cookies.json").toString();

        Thread worker = new Thread(() -> runLogin(session), "douyin-qr-" + session.id);
        worker.setDaemon(true);
        session.worker = worker;
        worker.start();
        return session;
    }

    private void runLogin(QrSessionService.QrSession session) {
        QrSessionService.QrSession s = session;
        try {
            set(s, "waiting", "正在打开抖音创作者平台...");
            Files.createDirectories(Paths.get(tempDir));
            Path qrPath = Paths.get(tempDir).resolve("douyin_qr_" + s.id + ".png");

            try (Playwright playwright = Playwright.create()) {
                Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(java.util.List.of("--no-sandbox", "--disable-dev-shm-usage")));
                try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setViewportSize(1440, 900)
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"))) {
                    Page page = context.newPage();
                    page.setDefaultTimeout(60_000);

                    page.navigate(UPLOAD_URL, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    page.waitForTimeout(2500);

                    // 尝试唤出登录弹窗
                    for (String label : new String[]{"登录", "扫码登录", "立即登录"}) {
                        try {
                            Locator btn = page.getByRole(
                                    com.microsoft.playwright.options.AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName(label).setExact(false)).first();
                            if (btn.count() > 0 && btn.isVisible()) {
                                btn.click();
                                page.waitForTimeout(1500);
                                break;
                            }
                        } catch (Exception ignored) {
                        }
                        try {
                            Locator txt = page.getByText(label, new Page.GetByTextOptions()
                                    .setExact(false)).first();
                            if (txt.count() > 0 && txt.isVisible()) {
                                txt.click();
                                page.waitForTimeout(1500);
                                break;
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    // 等待二维码真正渲染：抖音把二维码画成 178px 左右的 data-url PNG。
                    // 不能用 class 名定位（抖音的 class 是构建哈希，会随版本变化）。
                    Locator qrImage = waitForQrImage(page);
                    if (qrImage == null) {
                        // 兜底：截取登录卡片区域（比整页更聚焦），仍找不到则整页
                        boolean captured = false;
                        for (String sel : new String[]{
                                "[class*='login-card']",
                                "[class*='login'] [class*='card']"}) {
                            try {
                                Locator node = page.locator(sel).first();
                                if (node.count() > 0 && node.isVisible()) {
                                    node.screenshot(new Locator.ScreenshotOptions().setPath(qrPath));
                                    captured = true;
                                    break;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        if (!captured) {
                            page.screenshot(new Page.ScreenshotOptions().setPath(qrPath));
                        }
                    } else {
                        // 截图前先滚动到可见位置，避免截到遮挡/错位区域
                        try {
                            qrImage.scrollIntoViewIfNeeded();
                            page.waitForTimeout(300);
                        } catch (Exception ignored) {
                        }
                        qrImage.screenshot(new Locator.ScreenshotOptions().setPath(qrPath));
                    }
                    s.qrImagePath = qrPath.toString();
                    // ready = 图片已落盘，前端可以开始加载
                    set(s, "ready", "请用手机抖音 App 扫描二维码并在手机上确认登录");

                    // 轮询登录结果（最长与 TTL 一致）
                    long deadline = System.currentTimeMillis() + s.ttlMs;
                    while (System.currentTimeMillis() < deadline) {
                        if (s.worker != null && s.worker.isInterrupted()) {
                            set(s, "failed", "登录已取消");
                            return;
                        }
                        Thread.sleep(1500);
                        String url = page.url();
                        String body = safeText(page);
                        boolean stillLogin = body.contains("扫码登录")
                                || body.contains("手机号登录")
                                || body.contains("请用抖音扫码")
                                || (url.contains("passport") && !url.contains(CREATOR_HOST));

                        if (!stillLogin && url.contains(CREATOR_HOST)) {
                            // 登录成功：保存 storage_state
                            Path cookiePath = Paths.get(s.cookiePath);
                            Files.createDirectories(cookiePath.getParent());
                            context.storageState(new BrowserContext.StorageStateOptions()
                                    .setPath(cookiePath));
                            set(s, "success", "登录成功，登录态已保存");
                            log.info("抖音扫码登录成功，登录态写入 {}", cookiePath);
                            return;
                        }
                    }
                    set(s, "expired", "二维码已过期，请重新生成");

                } finally {
                    try {
                        browser.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.error("抖音扫码登录失败", e);
            set(s, "failed", "扫码登录失败: " + e.getMessage());
        }
    }

    private static void set(QrSessionService.QrSession s, String status, String message) {
        s.status = status;
        s.message = message;
    }

    /**
     * 等待二维码图片渲染完成。
     *
     * <p>抖音登录页把二维码绘制成一张 base64 data-url 的 PNG，尺寸约 178×178。
     * 不能用 class 名定位（class 是构建哈希，随版本变化），因此按以下条件筛选：</p>
     * <ul>
     *   <li>data-url PNG 且可见</li>
     *   <li>接近方形、边长 100~300</li>
     *   <li><b>完整位于视口内</b>（页面里存在同尺寸但被滚出视口的元素，必须排除）</li>
     * </ul>
     *
     * @return 找到则返回该 Locator，超时返回 null
     */
    private Locator waitForQrImage(Page page) {
        long deadline = System.currentTimeMillis() + QR_WAIT_TIMEOUT_MS;
        int viewportHeight = 900;
        try {
            Object vh = page.evaluate("() => window.innerHeight");
            if (vh instanceof Number n) viewportHeight = n.intValue();
        } catch (Exception ignored) {
        }

        while (System.currentTimeMillis() < deadline) {
            try {
                Locator candidates = page.locator("img[src^='data:image/png']");
                int count = candidates.count();
                Locator best = null;
                double bestArea = 0;
                for (int i = 0; i < count; i++) {
                    Locator node = candidates.nth(i);
                    if (!node.isVisible()) continue;
                    var box = node.boundingBox();
                    if (box == null) continue;
                    double w = box.width;
                    double h = box.height;
                    // 接近方形、边长 100~300
                    if (w < 100 || h < 100 || w > 300 || h > 300) continue;
                    if (Math.abs(w - h) > 20) continue;
                    // 必须在视口内：负坐标或超出视口的是被滚出屏幕的同尺寸元素
                    if (box.y < 0 || box.x < 0) continue;
                    if (box.y + h > viewportHeight) continue;
                    if (w * h > bestArea) {
                        bestArea = w * h;
                        best = node;
                    }
                }
                if (best != null) {
                    return best;
                }
            } catch (Exception ignored) {
            }
            page.waitForTimeout(1000);
        }
        return null;
    }

    private static String safeText(Page page) {
        try {
            String t = page.locator("body").innerText();
            return t == null ? "" : (t.length() > 2000 ? t.substring(0, 2000) : t);
        } catch (Exception e) {
            return "";
        }
    }
}

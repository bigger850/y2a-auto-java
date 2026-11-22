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

                    // 截取二维码：优先二维码容器，否则整页（前端可展示整页截图）
                    boolean captured = false;
                    for (String sel : new String[]{
                            "div[class*='qrcode']", "canvas", ".qrcode",
                            "[class*='scan'] img", "img[src*='qrcode']"}) {
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
                    s.qrImagePath = qrPath.toString();
                    set(s, "waiting", "请用手机抖音 App 扫描二维码并在手机上确认登录");

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

    private static String safeText(Page page) {
        try {
            String t = page.locator("body").innerText();
            return t == null ? "" : (t.length() > 2000 ? t.substring(0, 2000) : t);
        } catch (Exception e) {
            return "";
        }
    }
}

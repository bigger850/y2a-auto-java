package com.y2a.server.uploader;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 抖音发布器（Playwright Java 模拟创作者平台）。
 *
 * <p>对齐 Python modules/douyin_uploader.py 的行为：读取 storage_state 登录态、
 * 打开上传页、选择视频、填标题与话题、可选封面、点发布/暂存、等待跳转确认。</p>
 *
 * <p>注意：抖音网页 DOM 会随平台改版变化，选择器采用多组兜底；
 * 失败时截图留在 storage/logs/douyin 便于排查。</p>
 */
@Component
public class DouyinPublisher {

    private static final Logger log = LoggerFactory.getLogger(DouyinPublisher.class);

    private static final String UPLOAD_URL = "https://creator.douyin.com/creator-micro/content/upload";
    private static final int TITLE_LIMIT = 30;
    private static final int TAG_LIMIT = 10;

    /** 发布结果。 */
    public record Result(boolean success, String itemId, String url, String error) {
        public static Result ok(String itemId, String url) {
            return new Result(true, itemId, url, null);
        }

        public static Result fail(String error) {
            return new Result(false, null, null, error);
        }
    }

    /**
     * 发布视频到抖音。
     *
     * @param video      视频文件
     * @param cover      封面（可为 null）
     * @param title      标题（截断到 30 字）
     * @param tags       话题标签
     * @param cookieFile Playwright storage_state JSON
     * @param headless   是否无头
     * @param draftOnly  仅存草稿
     * @param screenshotDir 失败截图目录
     */
    public Result publish(Path video, Path cover, String title, List<String> tags,
                          Path cookieFile, boolean headless, boolean draftOnly,
                          Path screenshotDir, Consumer<Double> progress,
                          Consumer<String> logger, AtomicBoolean cancelFlag) {
        if (cookieFile == null || !Files.isRegularFile(cookieFile)) {
            return Result.fail("抖音登录态文件不存在，请在设置页扫码登录");
        }
        if (video == null || !Files.isRegularFile(video)) {
            return Result.fail("视频文件不存在");
        }

        String safeTitle = normalizeTitle(title);
        List<String> cleanTags = cleanTags(tags);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));

            try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setStorageStatePath(cookieFile)
                    .setViewportSize(1440, 900))) {
                Page page = context.newPage();
                page.setDefaultTimeout(120_000);

                log(logger, "打开抖音创作者平台上传页");
                page.navigate(UPLOAD_URL, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForTimeout(2500);

                // 登录态检测
                String bodyText = safeInnerText(page);
                if (bodyText.contains("扫码登录") || bodyText.contains("手机号登录")) {
                    return Result.fail("抖音登录态已失效，请在设置页重新扫码登录");
                }
                if (progress != null) progress.accept(10.0);

                // 选择视频文件
                Locator fileInput = locateFileInput(page);
                if (fileInput == null) {
                    screenshot(page, screenshotDir, "no_file_input");
                    return Result.fail("未找到抖音上传控件（页面可能改版），请查看失败截图");
                }
                fileInput.setInputFiles(video);
                log(logger, "视频已提交，等待上传完成");
                if (progress != null) progress.accept(20.0);

                // 等待上传完成（出现标题输入或"重新上传"）
                boolean uploaded = waitUploadDone(page, cancelFlag);
                if (!uploaded) {
                    screenshot(page, screenshotDir, "upload_timeout");
                    return Result.fail("等待抖音视频上传完成超时");
                }
                if (progress != null) progress.accept(60.0);

                // 填标题
                fillTitle(page, safeTitle, logger);

                // 填话题
                if (!cleanTags.isEmpty()) {
                    fillTags(page, cleanTags, logger);
                }

                // 封面
                if (cover != null && Files.isRegularFile(cover)) {
                    trySetCover(page, cover, logger);
                }
                page.waitForTimeout(800);
                if (progress != null) progress.accept(80.0);

                // 发布
                boolean clicked = clickPublish(page, draftOnly, logger);
                if (!clicked) {
                    screenshot(page, screenshotDir, "no_publish_button");
                    return Result.fail("未找到发布按钮（页面可能改版）");
                }

                // 等待跳转确认
                String finalUrl = waitPublishDone(page, cancelFlag);
                if (finalUrl == null) {
                    screenshot(page, screenshotDir, "publish_unconfirmed");
                    return Result.fail("已点击发布，但未确认成功（请到创作中心核实，避免重复发布）");
                }

                // 回写登录态延长有效期
                try {
                    context.storageState(new BrowserContext.StorageStateOptions().setPath(cookieFile));
                } catch (Exception ignored) {
                }

                if (progress != null) progress.accept(100.0);
                String itemId = extractItemId(finalUrl);
                log(logger, "抖音发布成功");
                return Result.ok(itemId, finalUrl);
            }
        } catch (Exception e) {
            log.error("抖音发布异常", e);
            return Result.fail("抖音发布异常: " + e.getMessage());
        }
    }

    // ---------------- 内部步骤 ----------------

    private Locator locateFileInput(Page page) {
        for (String sel : new String[]{
                "input[type='file'][accept*='video']",
                "div[class*='container'] input[type='file']",
                "input[type='file']"}) {
            try {
                Locator loc = page.locator(sel).first();
                if (loc.count() > 0) {
                    return loc;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private boolean waitUploadDone(Page page, AtomicBoolean cancelFlag) {
        long deadline = System.currentTimeMillis() + 600_000;
        while (System.currentTimeMillis() < deadline) {
            if (cancelFlag != null && cancelFlag.get()) return false;
            String text = safeInnerText(page);
            if (text.contains("上传失败") || text.contains("视频格式不支持")) return false;
            for (String sel : new String[]{
                    "[contenteditable='true']",
                    "input[placeholder*='标题']",
                    "div:has-text('重新上传')",
                    "div:has-text('作品标题')"}) {
                try {
                    if (page.locator(sel).count() > 0) return true;
                } catch (Exception ignored) {
                }
            }
            page.waitForTimeout(2000);
        }
        return false;
    }

    private void fillTitle(Page page, String title, Consumer<String> logger) {
        for (String sel : new String[]{
                "[contenteditable='true'].notranslate",
                "div[contenteditable='true']",
                "input[placeholder*='标题']"}) {
            try {
                Locator loc = page.locator(sel).first();
                if (loc.count() > 0 && loc.isVisible()) {
                    loc.click();
                    page.keyboard().press("Control+A");
                    page.keyboard().insertText(title);
                    log(logger, "标题已填写");
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        log(logger, "未找到标题输入框，跳过");
    }

    private void fillTags(Page page, List<String> tags, Consumer<String> logger) {
        for (String sel : new String[]{
                "div.zone-container input",
                "input[placeholder*='话题']",
                "div[contenteditable='true']"}) {
            try {
                Locator loc = page.locator(sel).first();
                if (loc.count() > 0 && loc.isVisible()) {
                    loc.click();
                    for (String tag : tags) {
                        page.keyboard().insertText("#" + tag);
                        page.keyboard().press("Space");
                        page.waitForTimeout(250);
                    }
                    log(logger, "话题已填写");
                    return;
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void trySetCover(Page page, Path cover, Consumer<String> logger) {
        try {
            for (String sel : new String[]{
                    "div:has-text('选择封面')", "button:has-text('更换封面')", "text=选择封面"}) {
                Locator loc = page.locator(sel).first();
                if (loc.count() > 0 && loc.isVisible()) {
                    loc.click();
                    break;
                }
            }
            page.waitForTimeout(800);
            Locator inputs = page.locator("input[type='file'][accept*='image']");
            if (inputs.count() == 0) {
                inputs = page.locator("input[type='file']");
            }
            if (inputs.count() == 0) return;
            Locator target = inputs.nth(inputs.count() - 1);
            target.setInputFiles(cover);
            page.waitForTimeout(1000);
            for (String name : new String[]{"完成", "确定", "确认"}) {
                try {
                    Locator btn = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName(name).setExact(true)).last();
                    if (btn.count() > 0 && btn.isVisible()) {
                        btn.click();
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            log(logger, "自定义封面已设置");
        } catch (Exception e) {
            log(logger, "设置封面失败（继续）: " + e.getMessage());
        }
    }

    private boolean clickPublish(Page page, boolean draftOnly, Consumer<String> logger) {
        String name = draftOnly ? "暂存" : "发布";
        for (String candidate : new String[]{name, "发布", "暂存"}) {
            try {
                Locator btn = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(candidate).setExact(true)).last();
                if (btn.count() > 0 && btn.isVisible()) {
                    try {
                        btn.click();
                    } catch (Exception e) {
                        btn.evaluate("el => el.click()");
                    }
                    log(logger, "已点击「" + candidate + "」");
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private String waitPublishDone(Page page, AtomicBoolean cancelFlag) {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            if (cancelFlag != null && cancelFlag.get()) return null;
            String url = page.url();
            String text = safeInnerText(page);
            if (url.contains("content/manage") || text.contains("发布成功") || text.contains("投稿成功")) {
                return url;
            }
            if (text.contains("发布失败") || text.contains("违规")) {
                return null;
            }
            page.waitForTimeout(1500);
        }
        return null;
    }

    private static String extractItemId(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[?&]item_id=([^&]+)").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    private static String safeInnerText(Page page) {
        try {
            String t = page.locator("body").innerText();
            return t == null ? "" : (t.length() > 4000 ? t.substring(0, 4000) : t);
        } catch (Exception e) {
            return "";
        }
    }

    private void screenshot(Page page, Path dir, String tag) {
        try {
            if (dir == null) return;
            Files.createDirectories(dir);
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(dir.resolve("douyin_" + tag + "_" + System.currentTimeMillis() + ".png")));
        } catch (Exception ignored) {
        }
    }

    private static String normalizeTitle(String raw) {
        String t = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        return t.length() <= TITLE_LIMIT ? t : t.substring(0, TITLE_LIMIT);
    }

    private static List<String> cleanTags(List<String> tags) {
        if (tags == null) return List.of();
        return tags.stream()
                .map(String::valueOf)
                .map(t -> t.trim().replaceAll("^#", ""))
                .filter(t -> !t.isEmpty())
                .distinct()
                .limit(TAG_LIMIT)
                .toList();
    }

    private void log(Consumer<String> logger, String message) {
        if (logger != null) logger.accept(message);
        log.info(message);
    }
}

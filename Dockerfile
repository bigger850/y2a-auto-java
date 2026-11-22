# Y2A-Auto Java 后端镜像
# 构建前先在项目根目录执行: mvn -DskipTests package
#
# 外部工具（方案 A）：
#   - ffmpeg / ffprobe：转码与字幕烧录
#   - yt-dlp：YouTube 下载（PyInstaller 单文件二进制，Java 用 ProcessBuilder 调用）
FROM eclipse-temurin:21-jre

ARG TARGETARCH=amd64
ARG YTDLP_VERSION=2026.10.26

WORKDIR /app

# 运行期依赖 + 外部工具（方案 A）
#   ffmpeg/ffprobe：用 Debian bookworm 官方包（含 libx264/libx265/libass，版本 5.1+）
#   yt-dlp：PyInstaller 单文件二进制（Java 用 ProcessBuilder 调用）
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates curl python3 \
        ffmpeg \
        fonts-noto-cjk fontconfig; \
    rm -rf /var/lib/apt/lists/*; \
    ffmpeg -version | head -n1; \
    ffprobe -version | head -n1; \
    arch="${TARGETARCH:-amd64}"; \
    ytdlp_url="https://github.com/yt-dlp/yt-dlp/releases/download/${YTDLP_VERSION}/yt-dlp_linux"; \
    if [ "$arch" = "arm64" ] || [ "$arch" = "aarch64" ]; then \
        ytdlp_url="https://github.com/yt-dlp/yt-dlp/releases/download/${YTDLP_VERSION}/yt-dlp_linux_aarch64"; \
    fi; \
    curl -fsSL "$ytdlp_url" -o /usr/local/bin/yt-dlp; \
    chmod 0755 /usr/local/bin/yt-dlp; \
    /usr/local/bin/yt-dlp --version

COPY y2a-server/target/y2a-server-0.1.0-SNAPSHOT.jar app.jar

# Playwright Chromium（抖音发布用）：装到共享目录，供非 root 用户 y2a 读取
# PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 让运行时 driver 不再尝试联网安装浏览器
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libatspi2.0-0 \
        libcups2 libdrm2 libdbus-1-3 libxkbcommon0 libxcomposite1 libxdamage1 \
        libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 libcairo2 \
        libasound2t64 libxshmfence1; \
    rm -rf /var/lib/apt/lists/*

USER root
# 用 jar 内自带的 playwright CLI 安装 chromium（版本与 SDK 匹配）
# Spring Boot fat jar 的依赖在 BOOT-INF/lib，用 PropertiesLauncher 指定主类
RUN set -eux; \
    cd /app; \
    java -Dloader.main=com.microsoft.playwright.CLI \
         -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
         install chromium; \
    chmod -R a+rX /ms-playwright

RUN useradd -m y2a \
    && mkdir -p /app/storage/downloads /app/storage/cookies /app/storage/logs /app/storage/temp \
    && chown -R y2a:y2a /app

USER y2a

# 让 yt-dlp 的 Python 检查不阻塞（外部工具已在镜像内）
ENV Y2A_YTDLP_PATH=/usr/local/bin/yt-dlp \
    Y2A_FFMPEG_PATH=/usr/bin/ffmpeg \
    Y2A_FFPROBE_PATH=/usr/bin/ffprobe

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

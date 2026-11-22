# Y2A-Auto (Java Edition)

把 YouTube 视频自动搬运到 **AcFun / bilibili / 抖音** 的一体化工具。
本仓库是原 Python 项目的 **Java 重构版**（Spring Boot 3.3 + Vue 3），业务逻辑 100% Java，
重型依赖（yt-dlp / ffmpeg）作为外部二进制调用。

---

## 特性

| 能力 | 说明 |
| --- | --- |
| 全流程自动化 | 下载 → 字幕 → 转码烧录 → 审核 → 投稿 |
| 三平台投稿 | AcFun / bilibili / 抖音，支持双平台串行投稿 |
| 三平台扫码登录 | AcFun、bilibili 纯协议扫码；抖音 Playwright 扫码 |
| Web 管理后台 | 任务列表、人工审核、YouTube 监控、9 分区设置页 |
| 实时进度 | SSE 推送 + 断连降级轮询 |
| 任务状态机 | 15 状态集中管控，禁止非法迁移 |
| 配置管理 | 208 项配置，PostgreSQL JSONB 存储，支持分类与归一化 |

---

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Spring Boot 3.3.5 / Java 21 |
| 持久层 | MyBatis-Plus 3.5.7 + PostgreSQL 16 + Flyway 10 |
| 鉴权 | Spring Security 6 + JWT (JJWT 0.12) |
| 前端 | Vue 3.5 + Vite 6 + TypeScript 5.7 + Pinia（复用原版 Bootstrap 5 样式） |
| 外部工具 | yt-dlp、ffmpeg/ffprobe、Chromium（Playwright） |
| 二维码 | ZXing 3.5.3 |

---

## 快速开始

### 前置要求

- Docker + Docker Compose
- （本地开发）JDK 21、Maven 3.9+、Node 20+

### 一键启动

```bash
# 1. 构建后端 jar
cd y2a-java
mvn -DskipTests package

# 2. 构建前端
cd y2a-web && npm install && npm run build && cd ..

# 3. 启动三容器
docker compose up -d
```

访问：

| 服务 | 地址 |
| --- | --- |
| Web 界面 | http://localhost:5173 （默认密码 `admin`） |
| 后端 API | http://localhost:18080 |
| Swagger | http://localhost:18080/swagger-ui.html |

### 首次配置

1. **设置页 → 账号与登录**：扫码登录 AcFun / bilibili / 抖音
2. **设置页 → 网络与下载**：填写 YouTube Cookies（可选，但能规避风控）
3. **设置页 → AI 服务**：填写 OpenAI 兼容端点（Phase 4 启用）
4. **首页 → 新建任务**：粘贴 YouTube 链接，选择目标平台

---

## 项目结构

```
y2a-java/
├── y2a-server/                  Spring Boot 后端
│   ├── src/main/java/com/y2a/server/
│   │   ├── common/              基础设施（安全/异常/常量/配置）
│   │   ├── controller/          12 个 REST 控制器（51 端点）
│   │   ├── service/             应用服务（状态机/流水线/配置/扫码…）
│   │   ├── domain/              领域模型
│   │   ├── repository/          数据访问（MyBatis-Plus）
│   │   ├── uploader/            三平台上传器 + 分发器
│   │   ├── tools/               外部工具封装（进程/HTTP/下载/转码）
│   │   └── engine/              处理引擎集成（预留）
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/        Flyway 迁移脚本
├── y2a-web/                     Vue 3 前端
│   ├── public/                  原版静态资源（CSS/Bootstrap/Icons）
│   └── src/                     9 个页面 + API + Store + 路由
├── tools/                       开发辅助脚本
├── Dockerfile
└── docker-compose.yml
```

---

## 开发

```bash
# 后端编译
mvn -q -DskipTests compile

# 后端单元测试
mvn -q -Dtest=TaskStatusMachineTest test

# 前端开发模式（热更新，代理到 8080）
cd y2a-web && npm run dev

# 前端类型检查 + 构建
cd y2a-web && npm run build
```

---

## 文档

- [进展报告](Y2A-Java-进展报告.md)：功能完成度、未完成清单、路线图
- [架构文档](Y2A-Java-架构文档.md)：总体架构、分层、数据模型、7 张时序图、扩展指南

---

## 安全提示

**严禁提交以下内容到仓库**（`.gitignore` 已排除）：

- `storage/cookies/` 下的任何 Cookie / 登录态文件
- `.env` 与本地配置覆盖文件
- 生产环境的 `JWT_SECRET`

生产部署前务必修改：
- `JWT_SECRET`（默认是开发占位值）
- `INITIAL_PASSWORD`（默认 `admin`）
- 为 Web 界面配置 HTTPS 反向代理

---

## 许可证

本项目遵循与原项目一致的 **GNU GPL v3** 许可。详见 [LICENSE](LICENSE)。

> 请遵守各平台服务条款，仅在合法合规前提下用于学习与研究。

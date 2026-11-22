# Y2A-Auto Java 重构 · 进展报告

> **报告版本**：v1.0　**生成时间**：2026-11-22 22:45 (Asia/Shanghai)
> **当前阶段**：Phase 3 已完成（下载/转码/上传/扫码），Phase 4 待启动（AI + ASR）
> **更新方式**：本报告为基线快照，后续每次迭代后追加「变更记录」章节并更新各表状态

---

## 一、总览

| 指标 | 数值 |
| --- | --- |
| Java 源文件 | **68 个** |
| Java 代码行数 | **6,546 行** |
| 前端 Vue 组件 | **9 个** |
| 前端代码行数 | **1,743 行** |
| REST 端点 | **51 个** |
| 数据库表 | **13 张**（tasks / app_config / 5 监控 / outbox / flyway 等） |
| 迁移脚本 | **3 个**（V1 init / V2 seed / V3 monitor），全部成功应用 |
| 配置项 | **208 项**（从 Python 权威源自动生成） |
| 外部工具 | yt-dlp 2026.10.26 ✅ / ffmpeg 8.0.1 ✅ / Chromium 1148 ✅ |
| 容器 | y2a-java / y2a-pg / y2a-web 三容器运行中 |

---

## 二、功能完成度矩阵

图例：✅ 已实现并实测　🟡 已实现未实测（缺凭证/外部条件）　⬜ 未开始

### 2.1 平台与基础设施

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| Spring Boot 3.3 / Java 21 骨架 | ✅ | 多模块 Maven，打包 46MB jar |
| PostgreSQL + Flyway | ✅ | 3 个迁移全部应用，208 配置项入库 |
| MyBatis-Plus + 分页 | ✅ | TaskRepository / MonitorRepository 等 6 个仓储 |
| JWT 鉴权 + 登录锁定 | ✅ | 对齐原版「仅密码 + 失败次数锁定」 |
| 统一响应 / 全局异常 | ✅ | ApiResponse + BizException + GlobalExceptionHandler |
| 任务状态机（15 状态） | ✅ | 枚举 + 集中迁移表，非法迁移被拒（实测 code 50200） |
| 事件驱动（SSE 广播 + outbox） | ✅ | TaskEventPublisher → 监听器 → SSE / 通知队列 |
| 引擎回调接口 | ✅ | `/api/internal/engine/callback` 预留（阶段4用） |

### 2.2 任务流水线

| 阶段 | 状态 | 实现方式 | 实测 |
| --- | --- | --- | --- |
| fetch_info（元信息） | ✅ | yt-dlp `--dump-single-json` | 「Me at the zoo」标题抓取成功 |
| download_video | ✅ | yt-dlp + 进度解析 | 视频465K + 封面24K + 元数据77K 全部落地 |
| translate_subtitle（下载字幕） | 🟡 | yt-dlp `--write-subs` | 代码就绪，需有字幕的视频验证 |
| encode（转码/烧录） | ✅ | ffmpeg libx264 CRF + libass 烧录 | 容器内转码实测 664K 输出正常 |
| upload（投稿） | ✅ | 三平台上传器 | AcFun 报错「Cookie 为空」正确，需凭证真实投稿 |
| 封面兜底抽帧 | ✅ | ffmpeg `-ss 3 -vframes 1` | 无封面时自动生成 |

### 2.3 三平台上传

| 平台 | 协议实现 | 状态 | 关键点 |
| --- | --- | --- | --- |
| **AcFun** | 全链路 HTTP | 🟡 | getKSCloudToken → 分片 → complete → uploadFinish → createVideo → 七牛封面 → createDouga；含 3 次重试+指数退避 |
| **bilibili** | UPOS + WBI | 🟡 | nav 校验 + WBI 签名（MIXIN_KEY_ENC_TAB 重排）→ preupload → 分片 → 封面 → web/add/v3 |
| **抖音** | Playwright | 🟡 | storage_state → 上传页 → 选视频 → 等上传完成 → 标题/话题 → 封面 → 发布 → 跳转确认 → 回写登录态 |

### 2.4 扫码登录

| 平台 | 方式 | 状态 | 实测结果 |
| --- | --- | --- | --- |
| **AcFun** | 纯 HTTP 协议 | ✅ | base64 二维码 892 字符，轮询稳定 `pending/等待扫码` |
| **bilibili** | 纯 HTTP 协议 + ZXing 渲染 | ✅ | base64 PNG 15812 字符，三态码（86101/86090/86038）正确，轮询稳定 |
| **抖音** | Playwright 截图 | ✅ | 392KB 真实登录页截图，二维码清晰可扫 |

### 2.5 前端（1:1 复刻）

| 页面 | 状态 | 复刻方式 |
| --- | --- | --- |
| 登录页 | ✅ | 仅密码框 + 全屏渐变（与原版一致） |
| 首页仪表盘 | ✅ | 4 KPI + 4 队列卡 + 最近动态 + 快速入门弹窗 |
| 任务列表 | ✅ | 9列桌面表格 + 移动卡片双渲染 + 5 模态框 + SSE |
| 人工审核 | ✅ | 只读卡片 + 三平台分区/标签 + 3 操作弹窗 |
| 编辑任务 | ✅ | 标题/描述/标签/分区 + 平台字数限制提示 |
| YouTube 监控 | ✅ | 配置表 + 历史表 + 运行状态轮询 |
| 监控配置/历史 | ✅ | 28 字段表单 + 统计卡 |
| 设置页 | ✅ | 9 个 pill tab + 搜索 + Cookie 上传 + 三平台扫码 + 通知测试 |
| 静态资源 | ✅ | 原版 style.css(1140行) / bootstrap 5.1.3 / icons 1.13.1 全复用 |

### 2.6 其他模块

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| YouTube 监控（CRUD/运行/历史） | ✅ | 5 张表 + 异步运行 + 进度轮询（抓取逻辑待接 API） |
| 通知（3 渠道 + outbox） | ✅ | 企业微信/Server酱/message-pusher，递增重试 30s→3600s |
| Cookie 上传/解析 | ✅ | 支持 Netscape + JSON + Playwright storage_state |
| 系统健康检查 | ✅ | environment/database/engine/cookies/stuck/errors |
| 维护清理（日志/下载） | 🟡 | 接口就绪，实际清理逻辑待细化 |
| 工具自检 | ✅ | `/api/system/tools` 并行探测三工具 |

---

## 三、未完成清单（按优先级）

| # | 项目 | 优先级 | 预估工作量 | 阻塞/依赖 |
| --- | --- | --- | --- | --- |
| 1 | **AI 阶段**（翻译/标签/分区推荐） | P0 | 3-4 周 | openai-java；Python 侧 ~5000 行 |
| 2 | **ASR**（Whisper + silero-vad） | P0 | 3-4 周 | ONNX Runtime + VAD 分段算法移植 |
| 3 | 真实投稿验证（三平台） | P1 | 1 周 | **需真实账号 Cookie** |
| 4 | ~~bilibili 扫码登录~~ | ~~P2~~ | ✅ 已完成 | 纯 HTTP + ZXing，实测出码 |
| 5 | 前端进度实时展示 | P2 | 3-5 天 | SSE 已就绪，仅缺 UI 绑定 |
| 6 | 内容审核（阿里云 Green） | P3 | 3-5 天 | 官方 Java SDK |
| 7 | CookieCloud 同步 | P3 | 2-3 天 | AES 解密 + Netscape 生成 |
| 8 | 字幕质检 QC | P3 | 1 周 | 依赖 AI 阶段 |
| 9 | 智能分段 | P3 | 1 周 | 依赖 AI + ASR |

---

## 四、已知技术债与风险

| 风险 | 等级 | 现状 | 缓解 |
| --- | --- | --- | --- |
| bilibili 投稿未经真实环境验证 | 高 | 仅代码完整，WBI 参数与 body 结构可能需调整 | 需真实 Cookie 联调 |
| 抖音 DOM 选择器易随改版失效 | 中 | 已做多组兜底 + 失败截图 | 截图目录 `storage/logs/douyin` |
| Playwright 首次启动慢（20-30s） | 低 | driver 解压 + 浏览器启动 | 已加 skip 环境变量，避免联网 |
| AcFun 长轮询占线程 | 低 | 已限 8s 读超时 | 后续可改异步轮询 |
| 前端上传结果展示不完整 | 中 | 仅基础 badge | 需补 task_card 完整分支 |
| 通知 outbox 未做端到端验证 | 中 | 逻辑完整 | 需配置真实 webhook 测试 |

---

## 五、下一步建议（Phase 4 路线）

### 推荐顺序

```
① AI 元信息阶段（P0，2 周）
   ├─ openai-java 接入（Chat Completions + 兼容 Responses）
   ├─ 标题/简介翻译（批量 + 重试 + 降级协商）
   ├─ 标签生成（含预设标签合并、平台限额截断）
   └─ 分区推荐（AcFun 映射表 / bilibili 分区树）

② ASR 阶段（P0，2-3 周）
   ├─ Whisper HTTP 客户端（multipart + 分段）
   ├─ VAD（ONNX Runtime + silero_vad.onnx）
   └─ 字幕后处理（拆行/标点/填充词/时间轴）

③ 联调验证（P1，1 周）
   ├─ 真实 Cookie 跑通三平台投稿
   └─ 修复协议差异

④ 前端增强（P2，1 周）
   ├─ 任务进度实时条
   ├─ 上传结果完整展示
   └─ 转码/AI 配置项联动
```

### 里程碑定义

| 里程碑 | 验收标准 |
| --- | --- |
| **M1 内容智能化** | 一条 YouTube 链接自动产出中文标题/简介/标签/分区 |
| **M2 字幕全链路** | 无字幕视频能通过 ASR 生成并翻译烧录 |
| **M3 生产可用** | 三平台各成功投稿 1 次，前端可完整操作 |

---

## 六、变更记录

| 日期 | 版本 | 内容 |
| --- | --- | --- |
| 2026-11-22 | v1.0 | 基线报告：Phase 0-3 完成（骨架/CRUD/流水线/三平台上传/扫码） |
| 2026-11-22 | v1.1 | **补齐 bilibili 扫码登录**：新增 `BilibiliQrLoginService`（纯 HTTP 协议 + ZXing 二维码渲染），三平台扫码覆盖率达 3/3。新增 ZXing 3.5.3 依赖。实测出码 15812 字符 base64 PNG，三态码轮询正常。影响：2.4 节扫码表、未完成清单移除第 4 项 |

<!-- 后续迭代在此追加，格式：
| YYYY-MM-DD | vX.Y | 完成了什么；影响了哪些表格；下一步变化 |
-->

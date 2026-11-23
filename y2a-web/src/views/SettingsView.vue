<script setup lang="ts">
// 1:1 复刻原版 settings.html：9 个 pill tab + 搜索 + 摘要卡 + 字段分组 + 保存进度
import { ref, reactive, onMounted, computed, nextTick } from 'vue'
import { request } from '@/api'

interface TabDef { id: string; icon: string; label: string; sections: SectionDef[] }
interface SectionDef { title: string; lead?: string; fields: FieldDef[] }
interface FieldDef { key: string; label: string; type?: string; options?: [string, string][]; hint?: string }

const TABS: TabDef[] = [
  { id: 'vtab-publish', icon: 'bi-sliders', label: '运行与投稿', sections: [
    { title: '自动化流程', fields: [
      { key: 'AUTO_MODE_ENABLED', label: '无人值守自动投稿', type: 'checkbox' },
      { key: 'TRANSLATE_TITLE', label: '翻译标题', type: 'checkbox' },
      { key: 'TRANSLATE_DESCRIPTION', label: '翻译简介', type: 'checkbox' },
      { key: 'GENERATE_TAGS', label: 'AI 生成标签', type: 'checkbox' },
      { key: 'RECOMMEND_PARTITION', label: 'AI 推荐分区', type: 'checkbox' },
      { key: 'RECOMMEND_PARTITION_WITH_COVER', label: '推荐分区时分析封面', type: 'checkbox' },
    ]},
    { title: '投稿策略', fields: [
      { key: 'UPLOAD_TARGET_DEFAULT', label: '默认投稿平台', type: 'select', options: [['acfun','AcFun'],['bilibili','bilibili'],['douyin','抖音'],['both','双平台（AcFun + bilibili）']] },
      { key: 'COVER_PROCESSING_MODE', label: '封面处理模式', type: 'select', options: [['crop','裁剪模式（优先清晰）'],['pad','填充模式（优先完整）']] },
      { key: 'UPLOAD_APPEND_REPOST_NOTICE', label: '追加转载声明', type: 'checkbox' },
      { key: 'DELETE_DOWNLOAD_FILES_AFTER_UPLOAD', label: '上传后删除本地文件', type: 'checkbox' },
      { key: 'PRESET_TAGS_ENABLED', label: '启用预设标签', type: 'checkbox' },
      { key: 'PRESET_TAGS', label: '预设标签', type: 'textarea', hint: '每行一个标签，也支持逗号 / 顿号分隔' },
      { key: 'YOUTUBE_UPLOADER_AS_FIRST_TAG', label: '上传者作为首标签', type: 'checkbox' },
    ]},
    { title: '内容审核（阿里云内容安全）', fields: [
      { key: 'CONTENT_MODERATION_ENABLED', label: '启用内容审核', type: 'checkbox' },
      { key: 'CONTENT_MODERATION_COVER_ENABLED', label: '启用封面审核', type: 'checkbox' },
      { key: 'ALIYUN_ACCESS_KEY_ID', label: 'Access Key ID' },
      { key: 'ALIYUN_ACCESS_KEY_SECRET', label: 'Access Key Secret', type: 'password' },
      { key: 'ALIYUN_CONTENT_MODERATION_REGION', label: '审核区域' },
    ]},
  ]},
  { id: 'vtab-accounts', icon: 'bi-person-badge', label: '账号与登录', sections: [
    { title: 'AcFun 账号', fields: [
      { key: 'ACFUN_COOKIES_PATH', label: 'Cookies 文件路径' },
      { key: 'acfun_cookies_file', label: '上传新的 Cookies 文件', type: 'file' },
    ]},
    { title: 'bilibili 账号', fields: [
      { key: 'BILIBILI_COOKIES_PATH', label: 'Cookies 文件路径' },
      { key: 'bilibili_cookies_file', label: '上传新的 Cookies 文件', type: 'file' },
      { key: 'BILIBILI_SUBMIT_API', label: '投稿接口', type: 'select', options: [['auto','自动（优先必剪，失败转 Web）'],['bcut_pc','仅必剪 PC 接口'],['web','仅 Web 投稿接口']] },
    ]},
    { title: '抖音账号', fields: [
      { key: 'DOUYIN_COOKIES_PATH', label: '登录态文件路径', hint: 'Playwright storage_state JSON，推荐用下方扫码登录自动生成' },
      { key: 'douyin_cookies_file', label: '上传新的登录态文件', type: 'file' },
      { key: 'DOUYIN_HEADLESS', label: '无头浏览器运行', type: 'checkbox' },
      { key: 'DOUYIN_SAVE_DRAFT_ONLY', label: '仅存草稿，不自动发布', type: 'checkbox' },
    ]},
    { title: 'YouTube', fields: [
      { key: 'YOUTUBE_COOKIES_PATH', label: 'Cookies 文件路径' },
      { key: 'youtube_cookies_file', label: '上传新的 Cookies 文件', type: 'file' },
    ]},
    { title: 'CookieCloud', fields: [
      { key: 'COOKIECLOUD_ENABLED', label: '启用 CookieCloud', type: 'checkbox' },
      { key: 'COOKIECLOUD_SERVER_URL', label: '服务地址' },
      { key: 'COOKIECLOUD_UUID', label: 'UUID' },
      { key: 'COOKIECLOUD_PASSWORD', label: '密码', type: 'password' },
      { key: 'COOKIECLOUD_CRYPTO_TYPE', label: '加密模式', type: 'select', options: [['auto','auto'],['legacy','legacy'],['aes-128-cbc-fixed','aes-128-cbc-fixed']] },
    ]},
  ]},
  { id: 'vtab-network', icon: 'bi-globe2', label: '网络与下载', sections: [
    { title: 'YouTube 下载与代理', fields: [
      { key: 'YOUTUBE_PROXY_ENABLED', label: '启用下载代理', type: 'checkbox' },
      { key: 'YOUTUBE_PROXY_URL', label: '代理地址' },
      { key: 'YOUTUBE_DOWNLOAD_QUALITY_MODE', label: '下载质量模式', type: 'select', options: [['highest','最高'],['manual','手动']] },
      { key: 'YOUTUBE_DOWNLOAD_MAX_HEIGHT', label: '最大分辨率', type: 'select', options: [['2160','2160'],['1440','1440'],['1080','1080'],['720','720'],['480','480'],['360','360']] },
      { key: 'YOUTUBE_DOWNLOAD_THREADS', label: '下载线程数', type: 'number' },
      { key: 'YOUTUBE_THROTTLED_RATE', label: '速度限制' },
    ]},
    { title: 'YouTube 监控 API', fields: [
      { key: 'YOUTUBE_API_KEY', label: 'Data API v3 密钥', type: 'password' },
      { key: 'YOUTUBE_API_PROXY_ENABLED', label: '启用 API 代理', type: 'checkbox' },
      { key: 'YOUTUBE_API_PROXY_URL', label: 'API 代理地址' },
    ]},
    { title: '下载内容清理', fields: [
      { key: 'DOWNLOAD_CLEANUP_ENABLED', label: '启用下载清理', type: 'checkbox' },
      { key: 'DOWNLOAD_CLEANUP_HOURS', label: '保留小时数', type: 'number' },
      { key: 'DOWNLOAD_CLEANUP_INTERVAL', label: '清理间隔（小时）', type: 'number' },
    ]},
  ]},
  { id: 'vtab-ai', icon: 'bi-robot', label: 'AI 服务与端点', sections: [
    { title: '全局模型', fields: [
      { key: 'OPENAI_BASE_URL', label: 'API 根地址' },
      { key: 'OPENAI_API_KEY', label: 'API Key', type: 'password' },
      { key: 'OPENAI_MODEL_NAME', label: '模型名称' },
      { key: 'OPENAI_THINKING_ENABLED', label: '允许模型思考', type: 'checkbox' },
      { key: 'OPENAI_REASONING_EFFORT', label: '思考力度', type: 'select', options: [['auto','auto（模型默认）'],['minimal','minimal'],['low','low'],['medium','medium'],['high','high'],['xhigh','xhigh']] },
      { key: 'OPENAI_TIMEOUT_SECONDS', label: '超时（秒）', type: 'number' },
    ]},
    { title: '字幕翻译专用覆盖', fields: [
      { key: 'SUBTITLE_OPENAI_BASE_URL', label: 'API 根地址' },
      { key: 'SUBTITLE_OPENAI_API_KEY', label: 'API Key', type: 'password' },
      { key: 'SUBTITLE_OPENAI_MODEL_NAME', label: '模型名称' },
    ]},
    { title: '兜底端点', fields: [
      { key: 'FALLBACK_OPENAI_BASE_URL', label: '兜底 API 地址' },
      { key: 'FALLBACK_OPENAI_API_KEY', label: '兜底 API Key', type: 'password' },
      { key: 'FALLBACK_OPENAI_MODEL_NAME', label: '兜底模型' },
      { key: 'AI_FAILOVER_TIMEOUT_SECONDS', label: '故障切换超时', type: 'number' },
    ]},
  ]},
  { id: 'vtab-asr', icon: 'bi-mic', label: '语音识别', sections: [
    { title: '引擎', fields: [
      { key: 'SPEECH_RECOGNITION_ENABLED', label: '启用语音识别', type: 'checkbox' },
      { key: 'SPEECH_RECOGNITION_PROVIDER', label: 'ASR 提供商', type: 'select', options: [['whisper','Whisper（OpenAI 兼容）'],['voxtral','Voxtral（Mistral）']] },
      { key: 'WHISPER_API_KEY', label: 'Whisper API Key', type: 'password' },
      { key: 'WHISPER_BASE_URL', label: 'Whisper Base URL' },
      { key: 'WHISPER_MODEL_NAME', label: 'Whisper 模型' },
      { key: 'WHISPER_LANGUAGE', label: '语言' },
      { key: 'VOXTRAL_API_KEY', label: 'Voxtral API Key', type: 'password' },
      { key: 'VOXTRAL_BASE_URL', label: 'Voxtral Base URL' },
    ]},
    { title: 'VAD 与音频分片', fields: [
      { key: 'VAD_ENABLED', label: '启用 VAD', type: 'checkbox' },
      { key: 'VAD_SILERO_THRESHOLD', label: 'Silero 阈值', type: 'number' },
      { key: 'AUDIO_CHUNK_WINDOW_S', label: '音频分片窗口', type: 'number' },
      { key: 'AUDIO_CHUNK_OVERLAP_S', label: '分片重叠', type: 'number' },
    ]},
  ]},
  { id: 'vtab-subtitle', icon: 'bi-badge-cc', label: '字幕翻译与排版', sections: [
    { title: '开关与来源', fields: [
      { key: 'SUBTITLE_TRANSLATION_ENABLED', label: '启用字幕翻译', type: 'checkbox' },
      { key: 'YOUTUBE_AUTO_GENERATED_SUBTITLES_ENABLED', label: '下载 YouTube 自动字幕', type: 'checkbox' },
      { key: 'SUBTITLE_SOURCE_LANGUAGE', label: '源语言', type: 'select', options: [['auto','auto'],['en','en'],['ja','ja'],['ko','ko'],['zh','zh']] },
      { key: 'SUBTITLE_TARGET_LANGUAGE', label: '目标语言', type: 'select', options: [['zh','zh'],['en','en'],['ja','ja'],['ko','ko']] },
      { key: 'SUBTITLE_FONT_NAME', label: '字幕字体' },
    ]},
    { title: '烧录输出', fields: [
      { key: 'SUBTITLE_EMBED_IN_VIDEO', label: '字幕嵌入视频', type: 'checkbox' },
      { key: 'SUBTITLE_KEEP_ORIGINAL', label: '保留原始字幕', type: 'checkbox' },
      { key: 'SUBTITLE_BATCH_SIZE', label: '翻译批次大小', type: 'number' },
      { key: 'SUBTITLE_MAX_WORKERS', label: '并发线程数', type: 'number' },
    ]},
    { title: '质检', fields: [
      { key: 'SUBTITLE_QC_ENABLED', label: '启用字幕质检', type: 'checkbox' },
      { key: 'SUBTITLE_QC_THRESHOLD', label: '质检分数下限', type: 'number' },
      { key: 'SUBTITLE_QC_SAMPLE_MAX_ITEMS', label: '抽样上限', type: 'number' },
    ]},
  ]},
  { id: 'vtab-media', icon: 'bi-camera-reels', label: '转码与媒体', sections: [
    { title: '视频编码', fields: [
      { key: 'VIDEO_ENCODER', label: '编码器', type: 'select', options: [['auto','auto'],['cpu','cpu'],['nvidia','nvidia'],['intel','intel'],['amd','amd']] },
      { key: 'VIDEO_CPU_CODEC', label: 'CPU 编码器', type: 'select', options: [['x264','libx264（H.264）'],['x265','libx265（H.265/HEVC）']] },
      { key: 'VIDEO_CPU_PRESET', label: 'CPU preset', type: 'select', options: [['ultrafast','ultrafast'],['veryfast','veryfast'],['medium','medium'],['slow','slow']] },
      { key: 'VIDEO_QUALITY_MODE', label: '质量模式', type: 'select', options: [['auto','auto'],['manual','manual']] },
      { key: 'VIDEO_QUALITY_VALUE', label: '质量值', type: 'number' },
      { key: 'VIDEO_HW_QUALITY_BOOST', label: '质量增强', type: 'checkbox' },
      { key: 'VIDEO_COLOR_METADATA_MODE', label: '色彩元数据', type: 'select', options: [['auto','auto'],['bt709','bt709'],['off','off']] },
      { key: 'VIDEO_CUSTOM_PARAMS_ENABLED', label: '自定义 FFmpeg 参数', type: 'checkbox' },
      { key: 'VIDEO_CUSTOM_PARAMS', label: '参数内容', type: 'textarea' },
      { key: 'FFMPEG_LOCATION', label: 'FFmpeg 路径' },
      { key: 'FFMPEG_AUTO_DOWNLOAD', label: '自动下载 FFmpeg', type: 'checkbox' },
    ]},
  ]},
  { id: 'vtab-notify', icon: 'bi-bell', label: '消息推送', sections: [
    { title: '通知总开关', fields: [
      { key: 'NOTIFY_ENABLED', label: '启用消息推送', type: 'checkbox' },
      { key: 'NOTIFY_EVENT_TASK_ADDED', label: '任务添加通知', type: 'checkbox' },
      { key: 'NOTIFY_EVENT_TASK_COMPLETED', label: '任务完成通知', type: 'checkbox' },
      { key: 'NOTIFY_EVENT_TASK_FAILED', label: '任务失败通知', type: 'checkbox' },
    ]},
    { title: '企业微信', fields: [
      { key: 'NOTIFY_WECOM_ENABLED', label: '启用企业微信', type: 'checkbox' },
      { key: 'NOTIFY_WECOM_WEBHOOK_URL', label: 'Webhook URL' },
    ]},
    { title: 'Server酱', fields: [
      { key: 'NOTIFY_SERVERCHAN_ENABLED', label: '启用 Server酱', type: 'checkbox' },
      { key: 'NOTIFY_SERVERCHAN_SENDKEY', label: 'SendKey', type: 'password' },
    ]},
    { title: 'message-pusher', fields: [
      { key: 'NOTIFY_MESSAGE_PUSHER_ENABLED', label: '启用 message-pusher', type: 'checkbox' },
      { key: 'NOTIFY_MESSAGE_PUSHER_SERVER', label: '服务地址' },
      { key: 'NOTIFY_MESSAGE_PUSHER_USERNAME', label: '用户名' },
      { key: 'NOTIFY_MESSAGE_PUSHER_TOKEN', label: 'Token', type: 'password' },
    ]},
  ]},
  { id: 'vtab-ops', icon: 'bi-shield-lock', label: '运维与安全', sections: [
    { title: '并发与维护', fields: [
      { key: 'MAX_CONCURRENT_TASKS', label: '最大并发任务数', type: 'number' },
      { key: 'MAX_CONCURRENT_UPLOADS', label: '最大并发上传数', type: 'number' },
      { key: 'LOG_CLEANUP_ENABLED', label: '启用日志清理', type: 'checkbox' },
      { key: 'LOG_CLEANUP_HOURS', label: '日志保留小时数', type: 'number' },
      { key: 'LOG_CLEANUP_INTERVAL', label: '清理间隔', type: 'number' },
    ]},
    { title: '密码安全', fields: [
      { key: 'password_protection_enabled', label: '启用密码保护', type: 'checkbox' },
      { key: 'new_password', label: '新密码', type: 'password' },
      { key: 'confirm_password', label: '确认密码', type: 'password' },
    ]},
    { title: '登录策略', fields: [
      { key: 'LOGIN_MAX_FAILED_ATTEMPTS', label: '最大失败次数', type: 'number' },
      { key: 'LOGIN_LOCKOUT_MINUTES', label: '锁定时长（分钟）', type: 'number' },
      { key: 'LOGIN_SESSION_TIMEOUT_MINUTES', label: '会话超时（分钟）', type: 'number' },
    ]},
  ]},
]

const settings = reactive<Record<string, any>>({})
const loading = ref(true)
const saving = ref(false)
const toast = ref('')
const searchQuery = ref('')
const activeTab = ref('vtab-publish')

// hash 联动（复刻原版）
function showTab(id: string) {
  activeTab.value = id
  location.hash = '#' + id
  nextTick(() => {
    const link = document.getElementById(id + '-tab')
    if (link && (window as any).bootstrap) (window as any).bootstrap.Tab.getOrCreateInstance(link).show()
  })
}
onMounted(async () => {
  const data = await request<Record<string, any>>({ method: 'GET', url: '/config' })
  Object.assign(settings, data)
  loading.value = false
  const hash = location.hash.replace('#', '')
  if (hash && TABS.some(t => t.id === hash)) showTab(hash)
})

async function save() {
  saving.value = true
  try {
    const payload: Record<string, any> = {}
    for (const t of TABS) for (const s of t.sections) for (const f of s.fields) {
      if (!(f.key in settings)) continue
      payload[f.key] = settings[f.key]
    }
    // 密码保护校验（复刻原版行为）
    if (payload.new_password || payload.confirm_password) {
      if (payload.new_password !== payload.confirm_password) {
        toast.value = '两次输入的密码不一致'
        return
      }
      payload.password = payload.new_password
    }
    delete payload.new_password
    delete payload.confirm_password
    for (const t of TABS) for (const s of t.sections) for (const f of s.fields) {
      if (f.type === 'file') delete payload[f.key]
    }
    const r = await request<{ updated: number }>({ method: 'PUT', url: '/config', data: payload })
    toast.value = `配置已成功保存（${r.updated} 项）`
  } catch (e: any) { toast.value = e?.message || '保存失败' }
  finally { saving.value = false; setTimeout(() => (toast.value = ''), 4500) }
}

/** Cookie / 登录态文件上传（独立于表单保存，选完即传）。 */
async function uploadCookie(fieldKey: string, ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const fd = new FormData()
  fd.append('field', fieldKey)
  fd.append('file', file)
  try {
    const resp = await fetch('/api/settings/upload-cookie', {
      method: 'POST', body: fd,
      headers: { Authorization: `Bearer ${localStorage.getItem('y2a_token') || ''}` },
    })
    const body = await resp.json()
    toast.value = body?.code === 0 ? `${file.name} 上传成功` : (body?.message || '上传失败')
  } catch (e: any) { toast.value = '上传失败: ' + e.message }
  input.value = ''
  setTimeout(() => (toast.value = ''), 4500)
}

// ---------------- 扫码登录 ----------------
const qrPanel = reactive<Record<string, { sessionId: string; status: string; imageSrc?: string; timer?: number }>>({})

async function startQr(platform: string) {
  try {
    const data = await request<{ session_id: string; image_base64?: string; mime_type?: string; message?: string }>({
      method: 'POST', url: `/settings/${platform}/qrcode/start`,
    })
    const panel: { sessionId: string; status: string; imageSrc?: string; timer?: number } = {
      sessionId: data.session_id,
      status: data.message || '等待扫码...',
    }
    if (data.image_base64) {
      panel.imageSrc = `data:${data.mime_type || 'image/png'};base64,${data.image_base64}`
    } else if (platform === 'douyin') {
      panel.imageSrc = `/api/settings/douyin/qrcode/image/${data.session_id}?t=${Date.now()}`
    }
    qrPanel[platform] = panel
    if (qrPanel[platform].timer) clearInterval(qrPanel[platform].timer)
    qrPanel[platform].timer = window.setInterval(() => pollQr(platform), 2000)
  } catch (e: any) { toast.value = e?.message || '获取二维码失败' }
}
async function pollQr(platform: string) {
  const panel = qrPanel[platform]
  if (!panel?.sessionId) return
  try {
    const data = await request<{ status: string; message: string }>({
      method: 'GET', url: `/settings/${platform}/qrcode/status/${panel.sessionId}`,
    })
    panel.status = data.message || data.status
    if (['done', 'failed', 'timeout'].includes(data.status)) {
      clearInterval(panel.timer)
      toast.value = data.message
      setTimeout(() => (toast.value = ''), 4500)
    }
  } catch {
    clearInterval(panel.timer)
  }
}

// ---------------- 通知渠道测试 ----------------
async function testChannel(channel: string) {
  try {
    const r = await request<{ message: string }>({
      method: 'POST', url: '/settings/notifications/test', data: { channel },
    })
    toast.value = r.message
  } catch (e: any) { toast.value = e?.message || '测试失败' }
  setTimeout(() => (toast.value = ''), 4500)
}

// ---------------- AI 连通性测试 ----------------
const aiTesting = ref(false)
const aiTestResult = ref('')

async function testAi() {
  aiTesting.value = true
  aiTestResult.value = ''
  try {
    const r = await request<{ ok: boolean; elapsedMs: number; model: string; reply: string }>({
      method: 'POST', url: '/ai/test',
    })
    aiTestResult.value = `✓ 连接正常（${r.elapsedMs}ms，模型 ${r.model}）：${r.reply || '正常'}`
  } catch (e: any) {
    aiTestResult.value = `✗ ${e?.message || '连接失败'}`
  } finally {
    aiTesting.value = false
  }
}

const filteredTabs = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return TABS
  const keys = q.split(/\s+/)
  return TABS.map(t => ({ ...t, sections: t.sections.map(s => ({ ...s,
    fields: s.fields.filter(f => keys.every(k => (f.label + f.key).toLowerCase().includes(k))),
  })).filter(s => s.fields.length) })).filter(t => t.sections.length)
})
</script>

<template>
  <div v-loading="loading" class="settings-container">
    <div class="settings-page-header">
      <h2>系统设置</h2>
      <p class="section-lead">在这里管理所有运行参数，修改后点击页面底部"保存设置"。</p>
      <div class="d-flex flex-wrap gap-2">
        <span class="badge bg-primary">运营优先</span><span class="badge bg-info">单页保存</span><span class="badge bg-secondary">高级项折叠</span>
      </div>
    </div>

    <div class="settings-search-bar mb-3">
      <input id="settings-search-input" class="form-control" v-model="searchQuery" placeholder="搜索设置项，如：代理、模型、密码、Whisper；空格分隔可缩小范围" />
      <button id="settings-search-clear" class="btn btn-sm btn-outline-secondary" @click="searchQuery = ''">清除</button>
      <div id="settings-search-hint" class="small text-muted">{{ searchQuery ? `找到 ${filteredTabs.reduce((n,t)=>n+t.sections.reduce((m,s)=>m+s.fields.length,0),0)} 项` : '' }}</div>
    </div>

    <div class="settings-summary-grid">
      <button class="settings-summary-card" @click="showTab('vtab-publish')"><i class="bi bi-sliders"></i> 运行与投稿</button>
      <button class="settings-summary-card" @click="showTab('vtab-accounts')"><i class="bi bi-person-badge"></i> 账号与登录</button>
      <button class="settings-summary-card" @click="showTab('vtab-ai')"><i class="bi bi-robot"></i> AI 服务</button>
      <button class="settings-summary-card" @click="showTab('vtab-notify')"><i class="bi bi-bell"></i> 消息推送</button>
    </div>

    <div class="row settings-layout">
      <div class="col-lg-3">
        <div class="settings-nav nav nav-pills flex-column" id="settings-nav" role="tablist">
          <a v-for="t in TABS" :key="t.id" class="nav-link" :id="t.id + '-tab'" :class="{ active: activeTab === t.id }"
             data-bs-toggle="pill" :href="'#' + t.id" role="tab" @click="activeTab = t.id">
            <i class="bi" :class="t.icon"></i> {{ t.label }}
          </a>
        </div>
      </div>
      <div class="col-lg-9">
        <form id="settings-form" @submit.prevent="save">
          <div class="tab-content" id="settings-tabContent">
            <div v-for="t in filteredTabs" :key="t.id" class="tab-pane fade" :id="t.id"
                 :class="{ show: activeTab === t.id, active: activeTab === t.id }" role="tabpanel">
              <div class="settings-stack">
                <section v-for="s in t.sections" :key="s.title" class="settings-section">
                  <div class="settings-section-header"><h3 class="mb-1">{{ s.title }}</h3><p v-if="s.lead" class="section-lead">{{ s.lead }}</p></div>
                  <div class="row g-4 settings-pane-grid"><div class="col-12"><article class="settings-card">
                    <div class="settings-card-header"><span class="section-eyebrow">设置</span><h4>{{ s.title }}</h4></div>
                    <div class="settings-card-body">
                      <div v-for="f in s.fields" :key="f.key" class="form-group mb-3">
                        <template v-if="f.type === 'checkbox'">
                          <div class="form-check form-switch"><input class="form-check-input" type="checkbox" :id="f.key" v-model="settings[f.key]" /><label class="form-check-label checkbox-label" :for="f.key">{{ f.label }}</label></div>
                        </template>
                        <template v-else-if="f.type === 'file'">
                          <label class="form-label" :for="f.key">{{ f.label }}</label>
                          <input type="file" class="form-control" :id="f.key" :accept="f.key.startsWith('douyin_') ? '.json' : undefined"
                                 @change="uploadCookie(f.key, $event)" />
                          <p class="help-text">选择文件后立即上传并更新上方路径。</p>
                        </template>
                        <template v-else>
                          <label class="form-label" :for="f.key">{{ f.label }}</label>
                          <select v-if="f.type === 'select'" class="form-select" :id="f.key" v-model="settings[f.key]">
                            <option v-for="opt in f.options" :key="opt[0]" :value="opt[0]">{{ opt[1] }}</option>
                          </select>
                          <textarea v-else-if="f.type === 'textarea'" class="form-control" :id="f.key" v-model="settings[f.key]" rows="4" :placeholder="f.hint"></textarea>
                          <input v-else class="form-control" :id="f.key" :type="f.type || 'text'" v-model="settings[f.key]" :placeholder="f.hint" />
                          <p v-if="f.hint && f.type !== 'textarea'" class="help-text">{{ f.hint }}</p>
                        </template>
                      </div>
                    </div>
                  </article></div></div>
                </section>

                <!-- 账号与登录：扫码登录入口 -->
                <section v-if="t.id === 'vtab-accounts'" class="settings-section">
                  <div class="settings-section-header"><h3 class="mb-1">扫码登录</h3>
                    <p class="section-lead">用手机 App 扫码并在手机上确认，登录态会自动写入对应 Cookies 文件。</p></div>
                  <div class="row g-4 settings-pane-grid">
                    <div v-for="p in [{ id: 'acfun', label: 'AcFun' }, { id: 'bilibili', label: 'bilibili' }, { id: 'douyin', label: '抖音' }]"
                         :key="p.id" class="col-12 col-xl-6">
                      <article class="settings-card"><div class="settings-card-header"><h4>{{ p.label }} 扫码登录</h4></div>
                        <div class="settings-card-body">
                          <div class="settings-inline-actions">
                            <button type="button" class="btn btn-outline-primary" @click="startQr(p.id)">
                              <i class="bi bi-qr-code"></i> 获取二维码登录
                            </button>
                            <span class="help-text">登录态通常 1~2 周过期，过期后重新扫码即可。</span>
                          </div>
                          <div v-if="qrPanel[p.id]" class="settings-qrcode-panel mt-2">
                            <img v-if="qrPanel[p.id].imageSrc" :src="qrPanel[p.id].imageSrc" alt="登录二维码" class="qrcode-img" />
                            <div class="mt-2"><span class="text-muted">{{ qrPanel[p.id].status }}</span></div>
                          </div>
                        </div>
                      </article>
                    </div>
                  </div>
                </section>

                <!-- AI 服务：连通性测试 -->
                <section v-if="t.id === 'vtab-ai'" class="settings-section">
                  <div class="settings-section-header"><h3 class="mb-1">连接测试</h3>
                    <p class="section-lead">保存配置后，可发送一个最小请求验证端点与密钥是否可用。</p></div>
                  <div class="row g-4 settings-pane-grid"><div class="col-12"><article class="settings-card"><div class="settings-card-body">
                    <div class="d-flex flex-wrap gap-2 align-items-center">
                      <button type="button" class="btn btn-outline-primary" :disabled="aiTesting" @click="testAi">
                        <i class="bi bi-lightning-charge"></i> {{ aiTesting ? '测试中...' : '测试 AI 连接' }}
                      </button>
                      <span class="help-text">使用「全局模型」中的配置发起一次对话请求。</span>
                    </div>
                    <div v-if="aiTestResult" class="mt-2">
                      <span :class="aiTestResult.startsWith('✓') ? 'text-success' : 'text-danger'">{{ aiTestResult }}</span>
                    </div>
                  </div></article></div></div>
                </section>

                <!-- 消息推送：渠道测试 -->
                <section v-if="t.id === 'vtab-notify'" class="settings-section">
                  <div class="settings-section-header"><h3 class="mb-1">渠道测试</h3>
                    <p class="section-lead">保存配置后，可发送一条测试消息验证渠道是否可用。</p></div>
                  <div class="row g-4 settings-pane-grid"><div class="col-12"><article class="settings-card"><div class="settings-card-body">
                    <div class="d-flex flex-wrap gap-2">
                      <button type="button" class="btn btn-outline-primary notify-test-btn" @click="testChannel('wecom')">测试企业微信</button>
                      <button type="button" class="btn btn-outline-primary notify-test-btn" @click="testChannel('serverchan')">测试 Server酱</button>
                      <button type="button" class="btn btn-outline-primary notify-test-btn" @click="testChannel('message_pusher')">测试 message-pusher</button>
                    </div>
                  </div></article></div></div>
                </section>
              </div>
            </div>
          </div>
          <div class="settings-actions-bar"><span class="help-text">修改后请保存</span><button id="save-settings-btn" type="submit" class="btn btn-primary" :disabled="saving">{{ saving ? '保存中...' : '保存设置' }}</button></div>
        </form>
      </div>
    </div>

    <div id="settings-toast-container" class="toast-container position-fixed top-0 end-0 p-3">
      <div v-if="toast" class="toast show" role="status"><div class="toast-body">{{ toast }}</div></div>
    </div>
  </div>
</template>

<script setup lang="ts">
// 1:1 复刻原版 tasks.html（含 partials/task_row.html 与 task_card.html）
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { taskApi } from '@/api/task'
import { request } from '@/api'
import type { Task, TaskPage } from '@/types/task'

const tasks = ref<Task[]>([])
const total = ref(0)
const page = ref(1)
const perPage = 20
const totalPages = ref(1)
const loading = ref(false)
const config = ref<Record<string, unknown>>({})
const refreshing = ref(false)

// 模态框状态
const addVisible = ref(false)
const deleteTarget = ref<Task | null>(null)
const deleteFiles = ref(true)
const clearVisible = ref(false)
const clearFiles = ref(true)
const retryVisible = ref(false)
const errorMsg = ref('')
const newUrl = ref('')
const newTarget = ref('acfun')

// SSE / fallback
let es: EventSource | null = null
let refreshTimer: number | undefined
let idleDelay = 8000

const STATUS_ICON: Record<string, string> = {
  downloading: 'bi-download', uploading: 'bi-cloud-upload',
  translating: 'bi-translate', translating_subtitle: 'bi-translate',
  asr_transcribing: 'bi-mic', encoding_video: 'bi-cpu',
  awaiting_manual_review: 'bi-eye', completed: 'bi-check-circle',
  failed: 'bi-x-circle', ready_for_upload: 'bi-cloud-arrow-up', pending: 'bi-pause-circle',
}
const STATUS_COLOR: Record<string, string> = {
  pending: 'secondary', downloading: 'primary', downloaded: 'info',
  asr_transcribing: 'info', translating_subtitle: 'info', encoding_video: 'info',
  translating: 'info', tagging: 'info', partitioning: 'info', moderating: 'warning',
  awaiting_manual_review: 'warning', ready_for_upload: 'primary', uploading: 'primary',
  completed: 'success', failed: 'danger',
}
const STATUS_LABEL: Record<string, string> = {
  pending: '等待处理', downloading: '下载中', downloaded: '已下载',
  asr_transcribing: '语音转写中', translating_subtitle: '翻译字幕中', encoding_video: '转码视频中',
  translating: '翻译中', tagging: '生成标签中', partitioning: '推荐分区中', moderating: '内容审核中',
  awaiting_manual_review: '待审核', ready_for_upload: '准备上传', uploading: '上传中',
  completed: '已完成', failed: '失败',
}

function statusLabel(s: string) { return STATUS_LABEL[s] || s }
function statusColor(s: string) { return STATUS_COLOR[s] || 'secondary' }
function statusIcon(s: string) { return STATUS_ICON[s] || 'bi-circle' }
function shortId(id: string, n = 8) { return id ? id.slice(0, n) + '…' : '' }
function displayTitle(t: Task) { return t.videoTitleTranslated || t.videoTitleOriginal || '未获取标题' }
function titleClamp(t: Task) { const s = displayTitle(t); return s.length > 60 ? s.slice(0, 60) + '…' : s }

function formatLocalDateTime(dt: string): string {
  if (!dt) return ''
  const d = new Date(dt.replace(' ', 'T'))
  if (isNaN(d.getTime())) return dt
  const now = new Date()
  const diff = (now.getTime() - d.getTime()) / 1000
  if (diff < 300) return '刚刚'
  if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前'
  if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  const y = new Date(now); y.setDate(y.getDate() - 1)
  if (d.toDateString() === y.toDateString()) return '昨天 ' + d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  if (diff < 7 * 86400) return d.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
  return d.toLocaleDateString('zh-CN')
}
function fullLocal(dt: string) { const d = new Date((dt || '').replace(' ', 'T')); return isNaN(d.getTime()) ? dt : d.toLocaleString('zh-CN') }

function hasProcessing() {
  return tasks.value.some((t) => ['downloading', 'uploading', 'translating', 'tagging', 'partitioning',
    'moderating', 'translating_subtitle', 'asr_transcribing', 'encoding_video', 'pending'].includes(t.status))
}
function determineDelay() { return tasks.value.length === 0 ? 15000 : hasProcessing() ? 2000 : 8000 }

async function load(manual = false) {
  if (refreshing.value) return
  refreshing.value = true
  if (manual) loading.value = true
  try {
    const data: TaskPage = await taskApi.list({ page: page.value, size: perPage })
    tasks.value = data.records ?? []
    total.value = data.total ?? 0
    totalPages.value = Math.max(1, Math.ceil(total.value / perPage))
    await nextTick(updateDateTimes)
  } catch (e) { console.error(e) }
  finally { refreshing.value = false; loading.value = false }
}
function scheduleNext() {
  clearTimeout(refreshTimer)
  refreshTimer = window.setTimeout(async () => { await load(false); scheduleNext() }, determineDelay())
}
function updateDateTimes() {
  document.querySelectorAll<HTMLElement>('.task-datetime').forEach((el) => {
    const raw = el.getAttribute('data-datetime') || ''
    el.textContent = formatLocalDateTime(raw)
    el.title = fullLocal(raw)
  })
}

let sseRetryTimer: number | undefined
let sseRetryCount = 0

/**
 * 建立 SSE 连接。
 *
 * 浏览器的 EventSource 无法携带 Authorization 头，因此先向受保护的
 * /api/tasks/stream-ticket 取一次性票据，再用 ?ticket=xxx 建立长连接。
 * 断连后按指数退避重连（最多 5 次），期间由轮询兜底。
 */
async function initSse() {
  try {
    const { ticket } = await request<{ ticket: string }>({
      method: 'POST', url: '/tasks/stream-ticket',
    })
    es = new EventSource(`/api/tasks/stream?ticket=${encodeURIComponent(ticket)}`)
    es.onopen = () => { sseRetryCount = 0 }
    es.onmessage = (e) => {
      try {
        const evt = JSON.parse(e.data)
        if (evt.type === 'welcome' || evt.type === 'heartbeat' || evt.type === 'connected') return
        load(true)
      } catch { /* ignore */ }
    }
    es.onerror = () => {
      es?.close()
      es = null
      if (sseRetryCount >= 5) return   // 放弃 SSE，交给轮询
      sseRetryCount++
      const delay = 1000 * Math.pow(1.5, sseRetryCount - 1)
      clearTimeout(sseRetryTimer)
      sseRetryTimer = window.setTimeout(() => { initSse() }, delay)
    }
  } catch {
    // 取票失败（未登录/网络问题）：保持轮询兜底，稍后重试一次
    if (sseRetryCount < 3) {
      sseRetryCount++
      clearTimeout(sseRetryTimer)
      sseRetryTimer = window.setTimeout(() => { initSse() }, 3000 * sseRetryCount)
    }
  }
}

// 动作
function confirmDelete(t: Task) { deleteTarget.value = t; deleteFiles.value = true }
function confirmClearAll() { clearVisible.value = true }
function confirmRetryFailed() { retryVisible.value = true }
function showError(msg: string) { errorMsg.value = msg }
async function doDelete() {
  if (!deleteTarget.value) return
  await taskApi.remove(deleteTarget.value.id, deleteFiles.value)
  deleteTarget.value = null
  load(true)
}
async function doClearAll() {
  await taskApi.clearAll(clearFiles.value)
  clearVisible.value = false
  load(true)
}
async function doRetryFailed() {
  await taskApi.retryFailed()
  retryVisible.value = false
  load(true)
}
async function startTask(t: Task) { await taskApi.changeStatus(t.id, 'downloading'); load(true) }
async function forceUpload(t: Task) { await taskApi.action(t.id, 'force-upload'); load(true) }
async function retryTrans(t: Task) { await taskApi.action(t.id, 'retry-translation'); load(true) }
async function abandon(t: Task) { await taskApi.action(t.id, 'abandon', { delete_files: false }); load(true) }

async function addTask() {
  if (!newUrl.value.includes('youtube')) { alert('请输入有效的 YouTube 链接'); return }
  await taskApi.create({ youtubeUrl: newUrl.value, uploadTarget: newTarget.value })
  addVisible.value = false; newUrl.value = ''; load(true)
}
function gotoPage(p: number) { if (p >= 1 && p <= totalPages.value) { page.value = p; load(true) } }
function copyText(s: string) { navigator.clipboard?.writeText(s) }

// 上传结果
function uploadBadge(t: Task): { text: string; href: string } | null {
  if (t.uploadTarget === 'douyin') {
    const r: any = t.douyinUploadResponse
    return r ? { text: '🎵 抖音已发布', href: r.url || 'https://creator.douyin.com/creator-micro/content/manage' } : null
  }
  if (t.uploadTarget === 'bilibili') {
    const r: any = t.bilibiliUploadResponse
    return r?.bvid ? { text: r.bvid, href: r.url || ('https://www.bilibili.com/video/' + r.bvid) } : null
  }
  const r: any = t.acfunUploadResponse
  return r?.ac_number ? { text: 'AC' + r.ac_number, href: 'https://www.acfun.cn/v/ac' + r.ac_number } : null
}

onMounted(async () => {
  await load(true)
  initSse()
  scheduleNext()
})
onUnmounted(() => {
  es?.close()
  clearTimeout(refreshTimer)
  clearTimeout(sseRetryTimer)
})
</script>

<template>
  <div class="tasks-container">
    <div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-4">
      <div class="d-flex align-items-center gap-2">
        <h2 class="mb-0">任务列表</h2>
        <div id="tasksRefreshIndicator" class="d-none" aria-hidden="true"><span class="spinner-border spinner-border-sm text-primary"></span></div>
      </div>
      <div class="d-flex gap-2 flex-wrap align-items-center">
        <button type="button" class="btn btn-primary" data-bs-toggle="modal" data-bs-target="#addTaskModal"><i class="bi bi-plus-circle"></i> 新建任务</button>
        <button id="retryFailedTasksBtn" type="button" class="btn btn-outline-danger" @click="confirmRetryFailed"><i class="bi bi-arrow-repeat"></i> 重试失败任务</button>
        <button type="button" class="btn btn-danger" @click="confirmClearAll"><i class="bi bi-trash"></i> 清除所有任务</button>
      </div>
    </div>

    <div id="tasksContent">
      <div v-if="tasks.length" class="modern-list">
        <!-- 桌面表格 -->
        <div class="table-responsive modern-table d-none d-md-block">
          <table class="table table-striped table-hover table-borderless table-sm align-middle tasks-table no-min-width">
            <colgroup>
              <col class="col-id"/><col class="col-title"/><col class="col-status"/><col class="col-time"/>
              <col class="col-platform"/><col class="col-partition"/><col class="col-tags"/><col class="col-result"/><col class="col-actions"/>
            </colgroup>
            <thead class="table-light"><tr>
              <th>ID</th><th>视频标题</th><th>状态</th><th>时间</th><th>平台</th><th>分区</th><th>标签</th><th>上传结果</th><th>操作</th>
            </tr></thead>
            <tbody>
              <tr v-for="t in tasks" :key="t.id" :data-task-id="t.id" :data-task-status="t.status" :class="'status-' + t.status">
                <td><button class="btn btn-link btn-sm p-0 copy-id-btn" :data-full-id="t.id" @click="copyText(t.id)" :title="t.id">{{ shortId(t.id) }}</button></td>
                <td><div class="title-clamp" :title="displayTitle(t)">{{ titleClamp(t) }}</div></td>
                <td><span class="badge" :class="'bg-' + statusColor(t.status)"><i class="bi" :class="statusIcon(t.status)"></i> {{ statusLabel(t.status) }}</span></td>
                <td><span class="task-datetime" :data-datetime="t.updatedAt">{{ formatLocalDateTime(t.updatedAt) }}</span></td>
                <td>
                  <span v-if="t.uploadTarget === 'bilibili'" class="badge-platform badge-platform-bilibili"><i class="bi bi-play-btn-fill"></i>bilibili</span>
                  <span v-else-if="t.uploadTarget === 'douyin'" class="badge-platform badge-platform-douyin"><i class="bi bi-music-note-beamed"></i>抖音</span>
                  <span v-else-if="t.uploadTarget === 'both'" class="badge-platform badge-platform-both"><i class="bi bi-collection-play-fill"></i>双平台</span>
                  <span v-else class="badge-platform badge-platform-acfun"><i class="bi bi-camera-video-fill"></i>AcFun</span>
                </td>
                <td><span class="text-muted small">{{ t.uploadTarget === 'douyin' ? '无分区' : '—' }}</span></td>
                <td><span v-if="t.tagsGenerated?.length" class="badge bg-secondary me-1 mb-1" v-for="tag in t.tagsGenerated.slice(0, 3)" :key="tag">{{ tag }}</span><span v-if="!t.tagsGenerated?.length" class="text-muted small">未生成</span></td>
                <td>
                  <a v-if="uploadBadge(t)" :href="uploadBadge(t)!.href" target="_blank" rel="noopener" class="badge bg-success text-decoration-none">{{ uploadBadge(t)!.text }}</a>
                  <span v-else class="text-muted small">—</span>
                </td>
                <td>
                  <div class="d-flex flex-wrap gap-1">
                    <a class="btn btn-sm btn-outline-primary task-action-btn" :href="'/tasks/' + t.id + '/edit'">编辑</a>
                    <button v-if="t.status === 'pending'" class="btn btn-sm btn-outline-success task-action-btn" @click="startTask(t)">开始</button>
                    <button v-if="t.status === 'awaiting_manual_review'" class="btn btn-sm btn-outline-warning task-action-btn" @click="forceUpload(t)">上传到{{ t.uploadTarget === 'douyin' ? '抖音' : t.uploadTarget === 'bilibili' ? 'bilibili' : t.uploadTarget === 'both' ? '双平台' : 'AcFun' }}</button>
                    <button class="btn btn-sm btn-outline-danger task-action-btn" @click="confirmDelete(t)">删除</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <!-- 移动卡片 -->
        <div class="d-block d-md-none">
          <div v-for="t in tasks" :key="t.id" class="card task-card mb-3" :data-task-id="t.id" :data-task-status="t.status" :class="'status-' + t.status">
            <div class="card-body">
              <div class="d-flex justify-content-between align-items-start">
                <h6 class="card-title mb-1 title-clamp-2">{{ displayTitle(t) }}</h6>
                <span class="badge" :class="'bg-' + statusColor(t.status)"><i class="bi" :class="statusIcon(t.status)"></i> {{ statusLabel(t.status) }}</span>
              </div>
              <div class="text-muted small mb-2"><i class="bi bi-clock"></i> <span class="task-datetime" :data-datetime="t.updatedAt">{{ formatLocalDateTime(t.updatedAt) }}</span></div>
              <div class="d-flex flex-wrap gap-2 mb-2">
                <span v-if="t.uploadTarget === 'douyin'" class="badge-platform badge-platform-douyin"><i class="bi bi-music-note-beamed"></i>抖音</span>
                <span v-else-if="t.uploadTarget === 'bilibili'" class="badge-platform badge-platform-bilibili"><i class="bi bi-play-btn-fill"></i>bilibili</span>
                <span v-else class="badge-platform badge-platform-acfun"><i class="bi bi-camera-video-fill"></i>AcFun</span>
              </div>
              <div class="d-flex flex-wrap gap-1">
                <a class="btn btn-sm btn-outline-primary" :href="'/tasks/' + t.id + '/edit'">编辑</a>
                <button v-if="t.status === 'pending'" class="btn btn-sm btn-outline-success" @click="startTask(t)">开始</button>
                <button class="btn btn-sm btn-outline-danger" @click="confirmDelete(t)">删除</button>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div v-else class="alert alert-info"><i class="bi bi-info-circle"></i> 暂无任务，请点击"新建任务"按钮添加任务。</div>

      <nav v-if="totalPages > 1" class="pagination d-flex justify-content-center">
        <button class="page-item page-link" :disabled="page === 1" @click="gotoPage(page - 1)">上一页</button>
        <button v-for="p in totalPages" :key="p" class="page-item page-link" :class="{ active: p === page }" @click="gotoPage(p)">{{ p }}</button>
        <button class="page-item page-link" :disabled="page === totalPages" @click="gotoPage(page + 1)">下一页</button>
      </nav>
    </div>

    <!-- 新建任务 -->
    <div class="modal fade" id="addTaskModal" tabindex="-1" aria-hidden="true">
      <div class="modal-dialog"><div class="modal-content">
        <div class="modal-header"><h5 class="modal-title"><i class="bi bi-plus-circle"></i> 新建任务</h5><button type="button" class="btn-close" data-bs-dismiss="modal"></button></div>
        <form @submit.prevent="addTask">
          <div class="modal-body">
            <div class="mb-3"><label for="youtube_url" class="form-label">YouTube视频URL</label>
              <input type="text" class="form-control" id="youtube_url" v-model="newUrl" required placeholder="https://www.youtube.com/watch?v=..." />
              <div class="form-text">支持标准YouTube视频链接</div></div>
            <div class="mb-3"><label for="upload_target" class="form-label">目标平台</label>
              <select class="form-select" id="upload_target" v-model="newTarget">
                <option value="acfun">AcFun</option><option value="bilibili">bilibili</option>
                <option value="douyin">抖音</option><option value="both">双平台（AcFun + bilibili）</option>
              </select>
              <div class="form-text">不选择时将使用系统默认平台。发布到抖音需先在设置页扫码登录。</div></div>
          </div>
          <div class="modal-footer"><button type="button" class="btn btn-secondary" data-bs-dismiss="modal"><i class="bi bi-x-circle"></i> 取消</button><button type="submit" class="btn btn-primary"><i class="bi bi-plus-circle"></i> 添加任务</button></div>
        </form>
      </div></div>
    </div>

    <!-- 删除 -->
    <div class="modal fade" id="deleteTaskModal" tabindex="-1" aria-hidden="true" :class="{ show: deleteTarget, 'd-block': deleteTarget }" :style="{ display: deleteTarget ? 'block' : 'none' }">
      <div class="modal-dialog"><div class="modal-content">
        <div class="modal-header"><h5 class="modal-title">确认删除</h5></div>
        <div class="modal-body"><p>确定删除任务 <strong>{{ deleteTarget ? shortId(deleteTarget.id) : '' }}</strong> 吗？</p>
          <div class="form-check"><input class="form-check-input" type="checkbox" id="deleteFiles" v-model="deleteFiles" checked /><label class="form-check-label" for="deleteFiles">同时删除任务文件</label></div>
        </div>
        <div class="modal-footer"><button type="button" class="btn btn-secondary" @click="deleteTarget = null">取消</button><button type="button" class="btn btn-danger" @click="doDelete"><i class="bi bi-trash"></i> 删除</button></div>
      </div></div>
    </div>

    <!-- 清除所有 -->
    <div class="modal fade" id="clearAllTasksModal" tabindex="-1" aria-hidden="true" :style="{ display: clearVisible ? 'block' : 'none' }">
      <div class="modal-dialog"><div class="modal-content">
        <div class="modal-header"><h5 class="modal-title">清除所有任务</h5></div>
        <div class="modal-body"><p>确定清除所有任务吗？此操作不可恢复。</p>
          <div class="form-check"><input class="form-check-input" type="checkbox" id="clearAllFiles" v-model="clearFiles" checked /><label class="form-check-label" for="clearAllFiles">同时删除任务文件</label></div>
        </div>
        <div class="modal-footer"><button type="button" class="btn btn-secondary" @click="clearVisible = false">取消</button><button type="button" class="btn btn-danger" @click="doClearAll">确认清除</button></div>
      </div></div>
    </div>

    <!-- 重试失败 -->
    <div class="modal fade" id="retryFailedTasksModal" tabindex="-1" aria-hidden="true" :style="{ display: retryVisible ? 'block' : 'none' }">
      <div class="modal-dialog"><div class="modal-content">
        <div class="modal-header"><h5 class="modal-title">重试失败任务</h5></div>
        <div class="modal-body"><p>确定重新调度所有失败的任务吗？</p></div>
        <div class="modal-footer"><button type="button" class="btn btn-secondary" @click="retryVisible = false">取消</button><button type="button" class="btn btn-warning" @click="doRetryFailed"><i class="bi bi-arrow-repeat"></i> 确认重试</button></div>
      </div></div>
    </div>

    <!-- 错误详情 -->
    <div class="modal fade" id="taskErrorModal" tabindex="-1" aria-hidden="true" :style="{ display: errorMsg ? 'block' : 'none' }">
      <div class="modal-dialog"><div class="modal-content">
        <div class="modal-header"><h5 class="modal-title">错误详情</h5><button type="button" class="btn-close" @click="errorMsg = ''"></button></div>
        <div class="modal-body"><pre class="task-error-content" id="taskErrorMessage">{{ errorMsg }}</pre></div>
        <div class="modal-footer"><button type="button" class="btn btn-secondary" @click="errorMsg = ''">关闭</button></div>
      </div></div>
    </div>
  </div>
</template>

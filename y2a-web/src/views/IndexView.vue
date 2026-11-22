<script setup lang="ts">
// 1:1 复刻原版 index.html
import { ref, onMounted, onUnmounted } from 'vue'
import { request } from '@/api'

interface Stats {
  total_tasks: number; awaiting_review: number; failed_total: number; pending_total: number
  ready_total: number; in_progress: number; completed_today: number; failed_today: number; created_today: number
}
interface RecentTask {
  id: string; title: string; status: string; updated_at: string
  upload_target: string; upload_id: string | null; status_color: string
}
const stats = ref<Stats>({
  total_tasks: 0, awaiting_review: 0, failed_total: 0, pending_total: 0,
  ready_total: 0, in_progress: 0, completed_today: 0, failed_today: 0, created_today: 0,
})
const recent = ref<RecentTask[]>([])

// 复刻 index.html 的 formatLocal 相对时间逻辑
function formatLocal(dt: string): string {
  if (!dt) return ''
  const d = new Date(dt.replace(' ', 'T'))
  if (isNaN(d.getTime())) return dt
  const now = new Date()
  const diff = (now.getTime() - d.getTime()) / 1000
  if (diff < 300) return '刚刚'
  if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前'
  const sameDay = d.toDateString() === now.toDateString()
  if (sameDay) return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  const yest = new Date(now); yest.setDate(yest.getDate() - 1)
  if (d.toDateString() === yest.toDateString()) return '昨天 ' + d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  if (diff < 7 * 86400) return d.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
  return d.toLocaleDateString('zh-CN')
}
function fullLocal(dt: string): string {
  if (!dt) return ''
  const d = new Date(dt.replace(' ', 'T'))
  return isNaN(d.getTime()) ? dt : d.toLocaleString('zh-CN')
}
let timer: number | undefined
function updateTimes() {
  document.querySelectorAll<HTMLElement>('.task-datetime').forEach((el) => {
    const raw = el.getAttribute('data-datetime') || ''
    el.textContent = formatLocal(raw)
    el.title = fullLocal(raw)
  })
}

async function load() {
  try {
    const data = await request<{ stats: Stats; recent_tasks: RecentTask[] }>({ method: 'GET', url: '/dashboard' })
    stats.value = data.stats || stats.value
    recent.value = data.recent_tasks || []
    setTimeout(updateTimes, 0)
  } catch (e) { console.error(e) }
}
onMounted(() => { load(); timer = window.setInterval(updateTimes, 60000) })
onUnmounted(() => { if (timer) clearInterval(timer) })

function shortId(id: string) { return id ? id.slice(0, 6) + '…' : '' }
function resultHref(t: RecentTask): string {
  if (t.upload_target === 'bilibili' && t.upload_id) return 'https://www.bilibili.com/video/' + t.upload_id
  return ''
}
</script>

<template>
  <div>
    <div class="home-hero p-3 p-md-4">
      <div class="d-flex flex-column flex-md-row align-items-md-center justify-content-between gap-2">
        <div>
          <h1 class="h3 mb-1">Y2A-Auto 仪表盘</h1>
          <div class="text-muted small">今日概览 · 实时掌握任务与转发情况</div>
        </div>
        <div class="d-flex flex-wrap gap-2">
          <a class="btn btn-primary" href="/tasks"><i class="bi bi-collection-play"></i> 任务列表</a>
          <a class="btn btn-warning" href="/manual_review"><i class="bi bi-patch-check"></i> 待审核</a>
          <a class="btn btn-outline-danger" href="/youtube_monitor"><i class="bi bi-broadcast-pin"></i> 监控</a>
          <button class="btn btn-outline-secondary" data-bs-toggle="modal" data-bs-target="#quickStartModal"><i class="bi bi-ui-checks-grid"></i> 快速入门</button>
        </div>
      </div>
    </div>

    <div class="row g-3 mt-2">
      <div class="col-6 col-md-3"><div class="card h-100"><div class="card-body d-flex align-items-center gap-3">
        <div class="home-kpi-icon success"><i class="bi bi-patch-check-fill"></i></div>
        <div><div class="text-muted small">今日完成</div><div class="h4 mb-0">{{ stats.completed_today }}</div></div>
      </div></div></div>
      <div class="col-6 col-md-3"><div class="card h-100"><div class="card-body d-flex align-items-center gap-3">
        <div class="home-kpi-icon danger"><i class="bi bi-shield-x"></i></div>
        <div><div class="text-muted small">今日失败</div><div class="h4 mb-0">{{ stats.failed_today }}</div></div>
      </div></div></div>
      <div class="col-6 col-md-3"><div class="card h-100"><div class="card-body d-flex align-items-center gap-3">
        <div class="home-kpi-icon primary"><i class="bi bi-node-plus"></i></div>
        <div><div class="text-muted small">今日新增</div><div class="h4 mb-0">{{ stats.created_today }}</div></div>
      </div></div></div>
      <div class="col-6 col-md-3"><div class="card h-100"><div class="card-body d-flex align-items-center gap-3">
        <div class="home-kpi-icon info"><i class="bi bi-lightning-charge"></i></div>
        <div><div class="text-muted small">进行中</div><div class="h4 mb-0">{{ stats.in_progress }}</div></div>
      </div></div></div>
    </div>

    <div class="row g-3 mt-1">
      <div class="col-6 col-md-3"><div class="card queue-stat-card h-100"><div class="card-body">
        <div class="queue-stat-icon"><i class="bi bi-hourglass-split"></i></div>
        <div class="text-muted small mt-2">待审核</div><div class="h5 mb-0">{{ stats.awaiting_review }}</div>
      </div></div></div>
      <div class="col-6 col-md-3"><div class="card queue-stat-card h-100"><div class="card-body">
        <div class="queue-stat-icon"><i class="bi bi-list-ul"></i></div>
        <div class="text-muted small mt-2">等待处理</div><div class="h5 mb-0">{{ stats.pending_total }}</div>
      </div></div></div>
      <div class="col-6 col-md-3"><div class="card queue-stat-card h-100"><div class="card-body">
        <div class="queue-stat-icon"><i class="bi bi-cloud-arrow-up"></i></div>
        <div class="text-muted small mt-2">准备上传</div><div class="h5 mb-0">{{ stats.ready_total }}</div>
      </div></div></div>
      <div class="col-6 col-md-3"><div class="card queue-stat-card h-100"><div class="card-body">
        <div class="queue-stat-icon"><i class="bi bi-bug"></i></div>
        <div class="text-muted small mt-2">失败总数</div><div class="h5 mb-0">{{ stats.failed_total }}</div>
      </div></div></div>
    </div>

    <div class="card mt-3">
      <div class="card-header bg-light d-flex align-items-center justify-content-between">
        <h5 class="mb-0"><i class="bi bi-broadcast-pin"></i> 最近动态</h5>
        <a class="btn btn-sm btn-outline-primary" href="/tasks"><i class="bi bi-arrow-right"></i> 前往任务</a>
      </div>
      <div class="card-body p-0">
        <div class="table-responsive">
          <table class="table table-hover align-middle mb-0">
            <thead class="table-light"><tr>
              <th class="col-narrow">#</th><th>标题</th><th>状态</th><th>更新时间</th><th>结果</th>
            </tr></thead>
            <tbody>
              <tr v-for="t in recent" :key="t.id">
                <td><span class="text-muted" :title="t.id">{{ shortId(t.id) }}</span></td>
                <td><div class="title-clamp title-clamp-2">{{ t.title || '未获取标题' }}</div></td>
                <td><span class="badge" :class="'bg-' + (t.status_color || 'secondary')">{{ t.status }}</span></td>
                <td><span class="task-datetime" :data-datetime="t.updated_at">{{ formatLocal(t.updated_at) }}</span></td>
                <td>
                  <a v-if="t.upload_target === 'bilibili' && t.upload_id" :href="resultHref(t)" target="_blank" rel="noopener" class="badge bg-success text-decoration-none">{{ t.upload_id }}</a>
                  <a v-else-if="t.upload_target === 'douyin' && t.upload_id" href="https://creator.douyin.com/creator-micro/content/manage" target="_blank" rel="noopener" class="badge bg-success text-decoration-none"><i class="bi bi-music-note-beamed"></i> 抖音已发布</a>
                  <a v-else-if="t.upload_target === 'acfun' && t.upload_id" :href="'https://www.acfun.cn/v/ac' + t.upload_id" target="_blank" rel="noopener" class="badge bg-success text-decoration-none">AC{{ t.upload_id }}</a>
                  <span v-else class="badge bg-secondary text-decoration-none">{{ t.upload_id || '—' }}</span>
                </td>
              </tr>
              <tr v-if="!recent.length"><td colspan="5" class="text-center text-muted py-3">暂无任务</td></tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="card-footer text-muted small">总任务数：{{ stats.total_tasks }}</div>
    </div>

    <div class="modal fade" id="quickStartModal" tabindex="-1" aria-hidden="true">
      <div class="modal-dialog modal-lg"><div class="modal-content">
        <div class="modal-header"><h5 class="modal-title">快速入门</h5><button type="button" class="btn-close" data-bs-dismiss="modal"></button></div>
        <div class="modal-body">
          <h6>1. 准备工作</h6><p class="text-muted">在「设置」页面配置目标投稿平台账号与 Cookie。</p>
          <h6>2. 添加任务</h6><p class="text-muted">在「任务列表」点击「新建任务」，粘贴 YouTube 链接。</p>
          <h6>3. 处理任务</h6><p class="text-muted">系统自动完成下载、字幕、翻译与转码。</p>
          <h6>4. 审核与上传</h6><p class="text-muted">在「人工审核」确认后上传到目标平台。</p>
        </div>
        <div class="modal-footer"><button type="button" class="btn btn-primary" data-bs-dismiss="modal">了解了</button></div>
      </div></div>
    </div>
  </div>
</template>

<script setup lang="ts">
// 1:1 复刻原版 youtube_monitor.html
import { ref, onMounted } from 'vue'
import { request } from '@/api'

interface Config { id: number; name: string; enabled: boolean; monitor_type: string; channel_mode: string; schedule_type: string; last_run_time: string; keywords: string; channel_ids: string }
interface History { id: number; config_id: number; run_time: string; config_name: string; video_title: string; channel_title: string; view_count: number; like_count: number; comment_count: number; video_id: string; added_to_tasks: boolean }
const configs = ref<Config[]>([])
const history = ref<History[]>([])
const running = ref<Record<number, boolean>>({})

async function load() {
  const data = await request<{ configs: Config[]; history: History[] }>({ method: 'GET', url: '/monitor' })
  configs.value = data.configs || []
  history.value = data.history || []
}
async function run(c: Config) {
  if (running.value[c.id]) return
  running.value[c.id] = true
  try {
    const data = await request<{ operation_id: string }>({ method: 'POST', url: `/monitor/config/${c.id}/run` })
    const op = data.operation_id
    const timer = setInterval(async () => {
      const s = await request<{ done: boolean }>({ method: 'GET', url: `/monitor/run-status/${op}` })
      if (s.done) { clearInterval(timer); running.value[c.id] = false; location.reload() }
    }, 1000)
  } catch { running.value[c.id] = false }
}
async function addToTasks(h: History) {
  await request({ method: 'POST', url: '/monitor/add_to_tasks', data: { video_id: h.video_id, config_id: h.config_id } })
  alert('已添加到任务队列')
}
async function del(c: Config) { if (confirm(`确定删除监控配置「${c.name}」吗？`)) { await request({ method: 'POST', url: `/monitor/config/${c.id}/delete` }); load() } }
async function restore() { if (confirm('确定从文件恢复监控配置吗？')) { await request({ method: 'POST', url: '/monitor/restore_configs' }); load() } }
async function clearAllHistory() { if (confirm('确定清除所有监控历史记录吗？')) { await request({ method: 'POST', url: '/monitor/history/clear_all' }); load() } }
onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex justify-content-between mb-4">
      <h2><i class="bi bi-youtube text-danger"></i> YouTube监控系统</h2>
      <div class="d-flex gap-2">
        <form @submit.prevent="restore"><button class="btn btn-outline-info"><i class="bi bi-arrow-clockwise"></i> 恢复配置</button></form>
        <a class="btn btn-primary" href="/youtube_monitor/config"><i class="bi bi-plus-circle"></i> 新建监控</a>
      </div>
    </div>

    <div class="row mb-5"><div class="col-12">
      <h4><i class="bi bi-list-ul"></i> 监控配置</h4>
      <div v-if="configs.length" class="table-responsive">
        <table class="table table-striped table-hover align-middle youtube-monitor-table">
          <thead class="table-dark"><tr><th class="col-name">名称</th><th>状态</th><th>监控类型</th><th>参数</th><th>调度类型</th><th>最后运行</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="c in configs" :key="c.id">
              <td data-label="名称">{{ c.name }}</td>
              <td data-label="状态"><span class="badge" :class="c.enabled ? 'bg-success' : 'bg-secondary'">{{ c.enabled ? '已启用' : '已禁用' }}</span></td>
              <td data-label="监控类型"><span class="badge bg-info">{{ c.monitor_type === 'channel_monitor' ? '频道监控' : '全网搜索' }}</span></td>
              <td data-label="参数" class="text-truncate-300">{{ c.keywords || c.channel_ids || '—' }}</td>
              <td data-label="调度类型">{{ c.schedule_type }} <span class="text-muted small">({{ c.channel_mode }})</span></td>
              <td data-label="最后运行"><span class="task-datetime" :data-datetime="c.last_run_time">{{ c.last_run_time || '从未运行' }}</span></td>
              <td data-label="操作"><div class="btn-group btn-group-sm">
                <button class="btn btn-outline-primary btn-icon" :disabled="running[c.id]" @click="run(c)" title="立即运行"><i class="bi" :class="running[c.id] ? 'bi-hourglass-split' : 'bi-play-fill'"></i></button>
                <a class="btn btn-outline-info btn-icon" :href="'/youtube_monitor/history/' + c.id" title="查看历史"><i class="bi bi-clock-history"></i></a>
                <a class="btn btn-outline-secondary btn-icon" :href="'/youtube_monitor/config/' + c.id + '/edit'" title="编辑"><i class="bi bi-pencil"></i></a>
                <button class="btn btn-outline-danger btn-icon" @click="del(c)" title="删除"><i class="bi bi-trash"></i></button>
              </div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="alert alert-info">还没有监控配置，<a href="/youtube_monitor/config">点击这里</a>创建第一个监控配置。</div>
    </div></div>

    <div class="row"><div class="col-12">
      <div class="d-flex justify-content-between align-items-center mb-2">
        <h4><i class="bi bi-clock-history"></i> 最近的监控记录</h4>
        <button class="btn btn-sm btn-outline-danger" @click="clearAllHistory"><i class="bi bi-trash"></i> 清除所有历史记录</button>
      </div>
      <div v-if="history.length" class="table-responsive">
        <table class="table table-striped history-table">
          <thead class="table-light"><tr><th>时间</th><th>配置名称</th><th>视频标题</th><th>频道</th><th>观看数</th><th>点赞数</th><th>评论数</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="h in history" :key="h.id">
              <td>{{ h.run_time }}</td><td>{{ h.config_name }}</td><td class="text-truncate-300">{{ h.video_title }}</td><td>{{ h.channel_title }}</td>
              <td>{{ h.view_count }}</td><td>{{ h.like_count }}</td><td>{{ h.comment_count }}</td>
              <td><span class="badge" :class="h.added_to_tasks ? 'bg-success' : 'bg-secondary'">{{ h.added_to_tasks ? '已添加' : '未添加' }}</span></td>
              <td><a class="btn btn-sm btn-outline-danger" :href="'https://www.youtube.com/watch?v=' + h.video_id" target="_blank" title="YouTube查看"><i class="bi bi-youtube"></i></a>
                <button class="btn btn-sm btn-outline-primary ms-1" v-if="!h.added_to_tasks" @click="addToTasks(h)">添加到任务</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="alert alert-info">还没有监控记录。</div>
    </div></div>
  </div>
</template>

<script setup lang="ts">
// 1:1 复刻原版 youtube_monitor_history.html
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { request } from '@/api'

const route = useRoute()
const configId = route.params.id as string
interface History { id: number; run_time: string; video_title: string; channel_title: string; view_count: number; like_count: number; comment_count: number; video_id: string; added_to_tasks: boolean }
const history = ref<History[]>([])
const stats = ref({ total_records: 0, added_to_tasks: 0, avg_views: 0, avg_likes: 0 })

async function load() {
  const data = await request<{ history: History[]; stats: typeof stats.value; config: any }>({ method: 'GET', url: `/monitor/history/${configId}` })
  history.value = data.history || []
  stats.value = data.stats || stats.value
}
async function clear() { if (confirm('确定清除该配置的监控历史吗？')) { await request({ method: 'POST', url: `/monitor/history/${configId}/clear` }); load() } }
async function add(h: History) { await request({ method: 'POST', url: '/monitor/add_to_tasks', data: { video_id: h.video_id, config_id: Number(configId) } }); load() }
onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex justify-content-between mb-4">
      <h2><i class="bi bi-clock-history"></i> 监控历史</h2>
      <div><button class="btn btn-sm btn-outline-danger" @click="clear"><i class="bi bi-trash"></i> 清除历史</button> <a class="btn btn-sm btn-secondary" href="/youtube_monitor">返回</a></div>
    </div>
    <div class="row g-3 mb-3">
      <div class="col-6 col-md-3"><div class="card"><div class="card-body text-center"><div class="text-muted small">总记录</div><div class="h4">{{ stats.total_records }}</div></div></div></div>
      <div class="col-6 col-md-3"><div class="card"><div class="card-body text-center"><div class="text-muted small">已添加任务</div><div class="h4">{{ stats.added_to_tasks }}</div></div></div></div>
      <div class="col-6 col-md-3"><div class="card"><div class="card-body text-center"><div class="text-muted small">平均观看</div><div class="h4">{{ stats.avg_views }}</div></div></div></div>
      <div class="col-6 col-md-3"><div class="card"><div class="card-body text-center"><div class="text-muted small">平均点赞</div><div class="h4">{{ stats.avg_likes }}</div></div></div></div>
    </div>
    <div class="table-responsive"><table class="table table-striped table-hover">
      <thead class="table-light"><tr><th>时间</th><th>视频标题</th><th>频道</th><th>观看数</th><th>点赞数</th><th>评论数</th><th>状态</th><th>操作</th></tr></thead>
      <tbody><tr v-for="h in history" :key="h.id">
        <td>{{ h.run_time }}</td><td class="text-truncate-300">{{ h.video_title }}</td><td>{{ h.channel_title }}</td>
        <td>{{ h.view_count }}</td><td>{{ h.like_count }}</td><td>{{ h.comment_count }}</td>
        <td><span class="badge" :class="h.added_to_tasks ? 'bg-success' : 'bg-secondary'">{{ h.added_to_tasks ? '已添加' : '未添加' }}</span></td>
        <td><a class="btn btn-sm btn-outline-danger" :href="'https://www.youtube.com/watch?v=' + h.video_id" target="_blank"><i class="bi bi-youtube"></i></a>
          <button v-if="!h.added_to_tasks" class="btn btn-sm btn-outline-primary ms-1" @click="add(h)">添加到任务</button></td>
      </tr><tr v-if="!history.length"><td colspan="8" class="text-center text-muted">暂无记录</td></tr></tbody>
    </table></div>
  </div>
</template>

<script setup lang="ts">
// 1:1 复刻原版 youtube_monitor_config.html（28 字段 + 3 模板按钮）
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request } from '@/api'

const route = useRoute()
const router = useRouter()
const editId = route.params.id as string | undefined
const error = ref('')

type Form = Record<string, any>
const defaults: Form = {
  name: '', enabled: true, monitor_type: 'youtube_search', channel_mode: 'latest',
  region_code: 'US', category_id: '0', time_period: 7, max_results: 10,
  min_view_count: 0, min_like_count: 0, min_comment_count: 0,
  keywords: '', exclude_keywords: '', title_keywords: '', title_match_mode: 'any',
  channel_ids: '', channel_keywords: '', exclude_channel_ids: '',
  min_duration: 0, max_duration: 0, schedule_type: 'manual', schedule_interval: 120,
  order_by: 'viewCount', start_date: '', end_date: '', latest_days: 7, latest_max_results: 20,
  rate_limit_requests: 20, rate_limit_window: 60, auto_add_to_tasks: false, video_types: 'video,short,live',
}
const form = ref<Form>({ ...defaults })

const templates: Record<string, Partial<Form>> = {
  trending: { monitor_type: 'youtube_search', order_by: 'viewCount', time_period: 7, schedule_type: 'interval' },
  channel_latest: { monitor_type: 'channel_monitor', channel_mode: 'latest', schedule_type: 'interval' },
  channel_historical: { monitor_type: 'channel_monitor', channel_mode: 'historical', schedule_type: 'manual' },
}
function applyTemplate(name: string) { Object.assign(form.value, templates[name]) }

async function load() {
  if (!editId) return
  const data = await request<{ config: Form }>({ method: 'GET', url: `/monitor/config/${editId}/edit` })
  Object.assign(form.value, defaults, data.config)
}
async function save() {
  error.value = ''
  if (!form.value.name?.trim()) { error.value = '名称不能为空'; return }
  const url = editId ? `/monitor/config/${editId}/edit` : '/monitor/config'
  await request({ method: 'POST', url, data: form.value })
  router.push('/youtube_monitor')
}
onMounted(load)
</script>

<template>
  <div>
    <h2 class="mb-4"><i class="bi bi-youtube text-danger"></i> {{ editId ? '编辑监控配置' : '新建监控配置' }}</h2>
    <div v-if="error" class="alert alert-danger">{{ error }}</div>
    <div class="mb-3 d-flex gap-2">
      <button class="btn btn-sm btn-outline-primary" @click="applyTemplate('trending')">热门模板</button>
      <button class="btn btn-sm btn-outline-primary" @click="applyTemplate('channel_latest')">频道最新模板</button>
      <button class="btn btn-sm btn-outline-primary" @click="applyTemplate('channel_historical')">频道历史模板</button>
    </div>
    <div class="card"><div class="card-body">
      <div class="row g-3">
        <div class="col-md-6"><label class="form-label">名称</label><input class="form-control" v-model="form.name" /></div>
        <div class="col-md-6"><label class="form-label">监控类型</label><select class="form-select" v-model="form.monitor_type"><option value="youtube_search">全网搜索</option><option value="channel_monitor">频道监控</option></select></div>
        <div class="col-md-6"><label class="form-label">频道模式</label><select class="form-select" v-model="form.channel_mode"><option value="latest">最新</option><option value="historical">历史</option></select></div>
        <div class="col-md-6"><label class="form-label">调度类型</label><select class="form-select" v-model="form.schedule_type"><option value="manual">手动</option><option value="interval">定时</option></select></div>
        <div class="col-md-6"><label class="form-label">关键词</label><input class="form-control" v-model="form.keywords" /></div>
        <div class="col-md-6"><label class="form-label">排除关键词</label><input class="form-control" v-model="form.exclude_keywords" /></div>
        <div class="col-md-6"><label class="form-label">标题包含关键词</label><input class="form-control" v-model="form.title_keywords" /></div>
        <div class="col-md-6"><label class="form-label">标题匹配模式</label><select class="form-select" v-model="form.title_match_mode"><option value="any">任一</option><option value="all">全部</option></select></div>
        <div class="col-md-6"><label class="form-label">频道 ID</label><input class="form-control" v-model="form.channel_ids" /></div>
        <div class="col-md-6"><label class="form-label">最大结果数</label><input type="number" class="form-control" v-model.number="form.max_results" /></div>
        <div class="col-md-6"><label class="form-label">时间范围（天）</label><input type="number" class="form-control" v-model.number="form.time_period" /></div>
        <div class="col-md-6"><label class="form-label">排序</label><select class="form-select" v-model="form.order_by"><option value="viewCount">观看数</option><option value="date">日期</option><option value="rating">评分</option></select></div>
        <div class="col-md-6"><label class="form-label">最小观看数</label><input type="number" class="form-control" v-model.number="form.min_view_count" /></div>
        <div class="col-md-6"><label class="form-label">最小点赞数</label><input type="number" class="form-control" v-model.number="form.min_like_count" /></div>
        <div class="col-md-6"><label class="form-label">最短时长（秒）</label><input type="number" class="form-control" v-model.number="form.min_duration" /></div>
        <div class="col-md-6"><label class="form-label">最长时长（秒）</label><input type="number" class="form-control" v-model.number="form.max_duration" /></div>
        <div class="col-md-6"><label class="form-label">视频类型</label><input class="form-control" v-model="form.video_types" /></div>
        <div class="col-md-6 form-check mt-4"><input class="form-check-input" type="checkbox" id="enabled" v-model="form.enabled" /><label class="form-check-label" for="enabled">启用</label></div>
        <div class="col-md-6 form-check mt-4"><input class="form-check-input" type="checkbox" id="autoAdd" v-model="form.auto_add_to_tasks" /><label class="form-check-label" for="autoAdd">自动添加到任务队列</label></div>
      </div>
    </div></div>
    <div class="mt-3 d-flex gap-2"><button class="btn btn-primary" @click="save">保存</button><a class="btn btn-secondary" href="/youtube_monitor">取消</a></div>
  </div>
</template>

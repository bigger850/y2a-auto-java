<script setup lang="ts">
// 1:1 复刻原版 edit_task.html
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request } from '@/api'
import type { Task } from '@/types/task'

const route = useRoute()
const router = useRouter()
const id = route.params.id as string
const task = ref<Task | null>(null)
const config = ref<Record<string, any>>({})
const title = ref('')
const description = ref('')
const tagsText = ref('')
const partition = ref('')
const saving = ref(false)

const PLATFORM_CONFIG: Record<string, { title: number; desc: number }> = {
  both: { title: 50, desc: 1000 }, bilibili: { title: 80, desc: 2000 },
  douyin: { title: 30, desc: 1000 }, acfun: { title: 50, desc: 1000 },
}
const cfg = computed(() => PLATFORM_CONFIG[task.value?.uploadTarget || 'acfun'] || PLATFORM_CONFIG.acfun)
const titleHint = computed(() => `当前平台标题上限 ${cfg.value.title} 字（当前 ${title.value.length} 字）`)
const descHint = computed(() => `当前平台简介上限 ${cfg.value.desc} 字（当前 ${description.value.length} 字）`)
function platformLabel(t: Task | null) {
  const x = t?.uploadTarget || 'acfun'
  return x === 'both' ? '双平台' : x === 'douyin' ? '抖音' : x === 'bilibili' ? 'bilibili' : 'AcFun'
}

async function load() {
  const data = await request<{ task: Task; config: Record<string, any> }>({ method: 'GET', url: `/tasks/${id}/edit` })
  task.value = data.task
  config.value = data.config || {}
  title.value = data.task.videoTitleTranslated || data.task.videoTitleOriginal || ''
  description.value = data.task.descriptionTranslated || data.task.descriptionOriginal || ''
  tagsText.value = (data.task.tagsGenerated || []).join(', ')
  partition.value = data.task.selectedPartitionId || ''
}
async function save(forceUpload: boolean) {
  saving.value = true
  try {
    await request({ method: 'PUT', url: `/tasks/${id}`, data: {
      video_title_translated: title.value,
      description_translated: description.value,
      tags_json: JSON.stringify(tagsText.value.split(/[,，\n]/).map(s => s.trim()).filter(Boolean)),
      selected_partition_id: partition.value,
      __force_upload: forceUpload,
    } })
    router.push(forceUpload ? '/manual_review' : `/tasks/${id}/edit`)
    if (!forceUpload) await load()
  } finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <div v-if="task" class="edit-task-container" :data-upload-target="task.uploadTarget">
    <h2 class="mb-4">编辑任务</h2>
    <p><strong>目标平台:</strong> {{ platformLabel(task) }}</p>

    <div class="card mb-4"><div class="card-header">基本信息</div><div class="card-body">
      <div class="mb-3"><label class="form-label">视频标题</label>
        <input type="text" class="form-control" v-model="title" />
        <div class="form-text" id="metadata-title-hint">{{ titleHint }}</div></div>
      <div class="mb-3"><label class="form-label">视频描述</label>
        <textarea class="form-control" rows="10" v-model="description"></textarea>
        <div class="form-text" id="metadata-description-hint">{{ descHint }}</div></div>
      <div class="mb-3"><label class="form-label">标签（逗号分隔）</label>
        <input type="text" class="form-control" v-model="tagsText" /></div>
    </div></div>

    <div class="card mb-4"><div class="card-header">分区设置</div><div class="card-body">
      <p v-if="task.uploadTarget === 'douyin'" class="text-muted mb-0"><i class="bi bi-info-circle"></i>抖音平台无需选择分区，发布时由平台自动分发。</p>
      <div v-else class="mb-3"><label class="form-label">选择分区</label>
        <input type="text" class="form-control" v-model="partition" placeholder="分区 ID（留空则自动推荐）" /></div>
    </div></div>

    <div class="d-flex gap-2 mb-4">
      <button class="btn btn-primary" :disabled="saving" @click="save(false)">保存修改</button>
      <button class="btn btn-success" :disabled="saving" @click="save(true)">上传到{{ platformLabel(task) }}</button>
      <a class="btn btn-secondary" href="/tasks">取消</a>
    </div>
    <div class="alert alert-info"><i class="bi bi-info-circle"></i> "保存修改"只更新标题、描述、分区和标签；"上传到{{ platformLabel(task) }}"会先保存当前修改，再在后台启动上传。</div>
  </div>
</template>

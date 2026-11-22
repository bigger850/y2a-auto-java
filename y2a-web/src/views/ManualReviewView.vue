<script setup lang="ts">
// 1:1 复刻原版 manual_review.html
import { ref, onMounted } from 'vue'
import { request } from '@/api'
import type { Task } from '@/types/task'

const tasks = ref<Task[]>([])
interface ParsedModeration { overall_pass?: boolean; title?: any; description?: any; cover?: any }
function moderation(t: Task): ParsedModeration | null {
  try { return typeof t.moderationResult === 'string' ? JSON.parse(t.moderationResult as string) : (t.moderationResult as any) }
  catch { return null }
}
function title(t: Task) { return t.videoTitleTranslated || t.videoTitleOriginal || '未获取标题' }
function platformLabel(t: Task) {
  return t.uploadTarget === 'both' ? '双平台' : t.uploadTarget === 'douyin' ? '抖音' : t.uploadTarget === 'bilibili' ? 'bilibili' : 'AcFun'
}
const retryTarget = ref<Task | null>(null)
const forceTarget = ref<Task | null>(null)
const abandonTarget = ref<Task | null>(null)
const abandonFiles = ref(true)

async function load() {
  tasks.value = await request<Task[]>({ method: 'GET', url: '/manual_review/tasks' })
}
async function doRetry() { await request({ method: 'POST', url: `/tasks/${retryTarget.value?.id}/retry-translation` }); retryTarget.value = null; load() }
async function doForce() { await request({ method: 'POST', url: `/tasks/${forceTarget.value?.id}/force-upload` }); forceTarget.value = null; load() }
async function doAbandon() { await request({ method: 'POST', url: `/tasks/${abandonTarget.value?.id}/abandon`, data: { delete_files: abandonFiles.value } }); abandonTarget.value = null; load() }
onMounted(load)
</script>

<template>
  <div class="review-container">
    <h2 class="mb-4"><i class="bi bi-check-square"></i> 人工审核列表</h2>
    <div v-if="tasks.length" class="row row-cols-1 row-cols-md-2 g-4">
      <div v-for="t in tasks" :key="t.id" class="col">
        <div class="card h-100">
          <div class="card-header d-flex justify-content-between align-items-center">
            <span class="badge bg-warning"><i class="bi bi-hourglass-split"></i> 待审核</span>
            <small class="text-muted">任务ID: {{ t.id.slice(0, 8) }}...</small>
          </div>
          <div v-if="t.coverPathLocal" class="card-img-container"><img :src="'/api/tasks/' + t.id + '/cover'" class="card-img-top" alt="封面" /></div>
          <div class="card-body">
            <div v-if="t.errorMessage" class="alert alert-warning"><i class="bi bi-exclamation-triangle"></i> {{ t.errorMessage }}</div>
            <h5 class="card-title">{{ title(t) }}</h5>
            <div v-if="t.descriptionTranslated || t.descriptionOriginal" class="mb-3">
              <h6><i class="bi bi-card-text"></i> 描述预览:</h6>
              <div class="desc-pre">{{ t.descriptionTranslated || t.descriptionOriginal }}</div>
            </div>
            <div v-if="moderation(t)" class="mb-3">
              <h6><i class="bi bi-shield-check"></i> 审核结果:</h6>
              <div v-if="moderation(t)!.overall_pass === false" class="alert alert-danger">审核不通过</div>
              <div v-else class="alert alert-success">审核通过</div>
              <listgroup>
                <div v-for="(val, key) in moderation(t)" :key="key" v-show="key !== 'overall_pass'" class="list-group-item">
                  {{ key }}: {{ val?.label || val?.reason || (typeof val === 'object' ? JSON.stringify(val) : val) }}
                </div>
              </listgroup>
            </div>
            <div class="mb-3"><h6><i class="bi bi-folder"></i> 当前分区:</h6>
              <span v-if="t.uploadTarget === 'douyin'" class="badge bg-secondary">无分区</span>
              <span v-else class="badge bg-info">{{ t.selectedPartitionId || t.recommendedPartitionId || '未设置' }}</span>
            </div>
            <div class="mb-3"><h6><i class="bi bi-tags"></i> 标签:</h6>
              <div class="tags-container"><span v-for="tag in t.tagsGenerated || []" :key="tag" class="badge bg-secondary me-1">{{ tag }}</span></div>
            </div>
          </div>
          <div class="card-footer">
            <div class="d-flex flex-wrap gap-2">
              <a class="btn btn-primary" :href="'/tasks/' + t.id + '/edit'"><i class="bi bi-pencil"></i> 修改后上传</a>
              <button class="btn btn-warning" @click="retryTarget = t"><i class="bi bi-arrow-clockwise"></i> 重新翻译</button>
              <button class="btn btn-success" @click="forceTarget = t"><i class="bi bi-upload"></i> 强制上传到{{ platformLabel(t) }}</button>
              <button class="btn btn-outline-danger" @click="abandonTarget = t"><i class="bi bi-trash"></i> 放弃任务</button>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div v-else class="alert alert-info"><i class="bi bi-info-circle"></i> 当前没有需要人工审核的任务。</div>

    <div class="modal fade" :style="{ display: retryTarget ? 'block' : 'none' }"><div class="modal-dialog"><div class="modal-content">
      <div class="modal-header"><h5 class="modal-title">重新翻译</h5></div>
      <div class="modal-body">确定重新翻译「{{ retryTarget ? title(retryTarget) : '' }}」的元信息吗？</div>
      <div class="modal-footer"><button class="btn btn-secondary" @click="retryTarget = null">取消</button><button class="btn btn-warning" @click="doRetry">确认</button></div>
    </div></div></div>
    <div class="modal fade" :style="{ display: forceTarget ? 'block' : 'none' }"><div class="modal-dialog"><div class="modal-content">
      <div class="modal-header"><h5 class="modal-title">强制上传</h5></div>
      <div class="modal-body">确定强制上传「{{ forceTarget ? title(forceTarget) : '' }}」吗？</div>
      <div class="modal-footer"><button class="btn btn-secondary" @click="forceTarget = null">取消</button><button class="btn btn-success" @click="doForce">确认上传</button></div>
    </div></div></div>
    <div class="modal fade" :style="{ display: abandonTarget ? 'block' : 'none' }"><div class="modal-dialog"><div class="modal-content">
      <div class="modal-header"><h5 class="modal-title">放弃任务</h5></div>
      <div class="modal-body"><p>确定放弃该任务吗？</p><div class="form-check"><input class="form-check-input" type="checkbox" id="abandonDeleteFiles" v-model="abandonFiles" /><label class="form-check-label" for="abandonDeleteFiles">同时删除任务文件</label></div></div>
      <div class="modal-footer"><button class="btn btn-secondary" @click="abandonTarget = null">取消</button><button class="btn btn-danger" @click="doAbandon">放弃</button></div>
    </div></div></div>
  </div>
</template>

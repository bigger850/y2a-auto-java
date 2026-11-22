import { defineStore } from 'pinia'
import { ref } from 'vue'
import { taskApi } from '@/api/task'
import type { Task, TaskPage } from '@/types/task'

export const useTaskStore = defineStore('task', () => {
  const tasks = ref<Task[]>([])
  const total = ref(0)
  const loading = ref(false)
  const page = ref(1)
  const size = ref(20)
  const statusFilter = ref('')
  const keyword = ref('')

  async function load() {
    loading.value = true
    try {
      const data: TaskPage = await taskApi.list({
        page: page.value,
        size: size.value,
        status: statusFilter.value || undefined,
        keyword: keyword.value || undefined,
      })
      tasks.value = data.records ?? []
      total.value = data.total ?? 0
    } finally {
      loading.value = false
    }
  }

  async function create(youtubeUrl: string, target: string) {
    const created = await taskApi.create({ youtubeUrl, uploadTarget: target })
    await load()
    return created
  }

  async function remove(id: string) {
    await taskApi.remove(id)
    await load()
  }

  return { tasks, total, loading, page, size, statusFilter, keyword, load, create, remove }
})

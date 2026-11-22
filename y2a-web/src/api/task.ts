import { request } from './index'
import type { Task, TaskPage } from '@/types/task'

export const taskApi = {
  list(params: { page: number; size: number; status?: string; target?: string; keyword?: string }) {
    return request<TaskPage>({ method: 'GET', url: '/tasks', params })
  },
  get(id: string) { return request<Task>({ method: 'GET', url: `/tasks/${id}` }) },
  fragment(id: string) {
    return request<{ taskId: string; rowHtml: string; cardHtml: string }>({ method: 'GET', url: `/tasks/${id}/fragment` })
  },
  create(data: { youtubeUrl: string; uploadTarget?: string }) {
    return request<Task>({ method: 'POST', url: '/tasks', data })
  },
  update(id: string, data: Record<string, unknown>) {
    return request<Task>({ method: 'PUT', url: `/tasks/${id}`, data })
  },
  changeStatus(id: string, status: string) {
    return request<Task>({ method: 'POST', url: `/tasks/${id}/status`, data: { status } })
  },
  action(id: string, action: string, data: Record<string, unknown> = {}) {
    return request<{ message: string }>({ method: 'POST', url: `/tasks/${id}/${action}`, data })
  },
  remove(id: string, deleteFiles = true) {
    return request<void>({ method: 'POST', url: `/tasks/${id}/delete`, data: { delete_files: deleteFiles } })
  },
  clearAll(deleteFiles = true) {
    return request<{ deleted: number }>({ method: 'POST', url: '/tasks/clear_all', data: { delete_files: deleteFiles } })
  },
  retryFailed() { return request<{ scheduled: number; total: number }>({ method: 'POST', url: '/tasks/retry_failed' }) },
  resetStuck() { return request<{ reset: number }>({ method: 'POST', url: '/tasks/reset_stuck' }) },
  coverUrl(id: string) { return `/api/tasks/${id}/cover` },
}

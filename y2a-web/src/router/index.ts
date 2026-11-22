import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    { path: '/', name: 'index', component: () => import('@/views/IndexView.vue'), meta: { requiresAuth: true } },
    { path: '/tasks', name: 'tasks', component: () => import('@/views/TasksView.vue'), meta: { requiresAuth: true } },
    { path: '/manual_review', name: 'manual_review', component: () => import('@/views/ManualReviewView.vue'), meta: { requiresAuth: true } },
    { path: '/tasks/:id/edit', name: 'edit_task', component: () => import('@/views/EditTaskView.vue'), meta: { requiresAuth: true } },
    { path: '/youtube_monitor', name: 'youtube_monitor', component: () => import('@/views/MonitorView.vue'), meta: { requiresAuth: true } },
    { path: '/youtube_monitor/config', name: 'monitor_config_new', component: () => import('@/views/MonitorConfigView.vue'), meta: { requiresAuth: true } },
    { path: '/youtube_monitor/config/:id/edit', name: 'monitor_config_edit', component: () => import('@/views/MonitorConfigView.vue'), meta: { requiresAuth: true } },
    { path: '/youtube_monitor/history/:id', name: 'monitor_history', component: () => import('@/views/MonitorHistoryView.vue'), meta: { requiresAuth: true } },
    { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue'), meta: { requiresAuth: true } },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.loggedIn) {
    return { name: 'login', query: { next: to.fullPath } }
  }
  if (to.name === 'login' && auth.loggedIn) {
    return { name: 'index' }
  }
  return true
})

export default router

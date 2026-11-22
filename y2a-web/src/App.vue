<script setup lang="ts">
// 1:1 复刻 base.html：顶栏 + flash + router-view + footer + 通知模态框
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const isLoginPage = computed(() => route.path === '/login')

function navActive(path: string, prefix = false) {
  return prefix ? route.path.startsWith(path) : route.path === path
}

async function logout() {
  await fetch('/api/auth/logout', { method: 'POST', headers: { 'X-CSRF-Token': '' } })
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div :class="{ 'login-fullscreen': isLoginPage }">
    <header v-if="!isLoginPage">
      <nav class="navbar navbar-expand-lg navbar-dark">
        <div class="container navbar-shell">
          <a class="navbar-brand d-flex align-items-center" href="/"><span>Y2A-Auto</span></a>
          <div class="navbar-utility ms-auto d-flex align-items-center gap-2">
            <form @submit.prevent="logout" class="d-inline">
              <button class="btn btn-outline-light btn-sm navbar-logout-btn" type="submit">
                <i class="bi bi-box-arrow-right"></i><span>退出登录</span>
              </button>
            </form>
            <button class="navbar-toggler" type="button" data-bs-toggle="collapse" data-bs-target="#navbarNav" aria-label="切换导航菜单">
              <span class="navbar-toggler-icon"></span>
            </button>
          </div>
          <div class="collapse navbar-collapse" id="navbarNav">
            <ul class="navbar-nav">
              <li class="nav-item">
                <a class="nav-link" :class="{ active: navActive('/') }" href="/"><i class="bi bi-house-heart nav-icon"></i> 首页</a>
              </li>
              <li class="nav-item">
                <a class="nav-link" :class="{ active: navActive('/tasks') }" href="/tasks"><i class="bi bi-clipboard-data nav-icon"></i> 任务列表</a>
              </li>
              <li class="nav-item">
                <a class="nav-link" :class="{ active: navActive('/manual_review') }" href="/manual_review"><i class="bi bi-patch-check nav-icon"></i> 人工审核</a>
              </li>
              <li class="nav-item">
                <a class="nav-link" :class="{ active: navActive('/youtube_monitor', true) }" href="/youtube_monitor"><i class="bi bi-broadcast-pin nav-icon"></i> YouTube监控</a>
              </li>
              <li class="nav-item">
                <a class="nav-link" :class="{ active: navActive('/settings') }" href="/settings"><i class="bi bi-sliders2 nav-icon"></i> 设置</a>
              </li>
            </ul>
          </div>
        </div>
      </nav>
    </header>

    <main v-if="!isLoginPage" class="container mt-4">
      <router-view />
    </main>
    <router-view v-else />

    <footer v-if="!isLoginPage" class="footer mt-5 py-3">
      <div class="container text-center">© {{ new Date().getFullYear() }} Y2A-Auto</div>
    </footer>

    <!-- 原版遗留的通知模态框骨架（保留 DOM，不接线） -->
    <div v-if="!isLoginPage" class="modal fade" id="notificationModal" tabindex="-1" aria-hidden="true">
      <div class="modal-dialog">
        <div class="modal-content">
          <div class="modal-header">
            <h5 class="modal-title"><i class="bi bi-bell"></i> 任务完成通知</h5>
            <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="关闭"></button>
          </div>
          <div class="modal-body" id="notificationBody"></div>
          <div class="modal-footer">
            <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">关闭</button>
            <a id="viewVideoBtn" class="btn btn-primary" target="_blank" rel="noopener">查看视频</a>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
// 1:1 复刻原版 login.html：只有密码字段 + 品牌 logo + 全屏渐变背景
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request } from '@/api'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const password = ref('')
const error = ref('')
const busy = ref(false)

onMounted(() => {
  document.body.classList.add('login-fullscreen')
  const el = document.getElementById('password') as HTMLInputElement | null
  if (el) { el.focus(); el.select() }
})

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const data = await request<{ token: string }>({
      method: 'POST', url: '/auth/login', data: { password: password.value },
    })
    auth.setToken(data.token)
    const next = (route.query.next as string) || '/'
    router.push(next)
  } catch (e: any) {
    error.value = e?.message || '登录失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-wrapper">
    <div class="login-card card shadow-sm">
      <div class="card-body">
        <div class="login-brand">
          <img src="/img/favicon.png" alt="Y2A-Auto" />
          <div class="title">Y2A-Auto</div>
        </div>
        <div class="text-center login-subtitle">请输入管理员密码以继续访问控制台</div>
        <div v-if="error" class="alert alert-danger mt-3" role="alert">{{ error }}</div>
        <form @submit.prevent="submit">
          <div class="form-floating mb-3">
            <input type="password" class="form-control" id="password" name="password"
                   placeholder="密码" v-model="password" required autofocus />
            <label for="password">密码</label>
          </div>
          <div class="d-grid">
            <button type="submit" class="btn btn-login" :disabled="busy">{{ busy ? '登录中...' : '登录' }}</button>
          </div>
        </form>
        <div class="login-help">忘记密码请联系系统管理员</div>
      </div>
    </div>
  </div>
</template>

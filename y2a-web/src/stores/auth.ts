import { defineStore } from 'pinia'
import { ref } from 'vue'
import { request } from '@/api'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem('y2a_token'))
  const loggedIn = ref<boolean>(!!token.value)

  function setToken(t: string) {
    token.value = t
    loggedIn.value = true
    localStorage.setItem('y2a_token', t)
  }

  async function login(password: string) {
    const data = await request<{ token: string }>({
      method: 'POST', url: '/auth/login', data: { password },
    })
    setToken(data.token)
  }

  function logout() {
    token.value = null
    loggedIn.value = false
    localStorage.removeItem('y2a_token')
  }

  return { token, loggedIn, setToken, login, logout }
})

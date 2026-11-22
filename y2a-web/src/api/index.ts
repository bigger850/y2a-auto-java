import axios, { type AxiosInstance } from 'axios'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId?: string
  timestamp?: string
}

const http: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('y2a_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  config.headers['X-CSRF-Token'] = token || ''
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResponse<unknown>
    if (body && typeof body.code === 'number' && body.code !== 0) {
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return resp
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('y2a_token')
      if (location.pathname !== '/login') location.href = '/login?next=' + encodeURIComponent(location.pathname)
    }
    return Promise.reject(error)
  },
)

export async function request<T>(config: Parameters<typeof http.request>[0]): Promise<T> {
  const resp = await http.request<ApiResponse<T>>(config)
  return resp.data.data
}

export default http

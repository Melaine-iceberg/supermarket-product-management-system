import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import router from '@/router'

/** 统一响应结构（与后端 IR-SW-01 约定一致） */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  timestamp: string
}

/** 分页结果（IR-COM-04）：列表接口须返回总条数以支持前端分页 */
export interface PageResult<T> {
  list: T[]
  total: number
}

export const TOKEN_KEY = 'smms_token'

const instance = axios.create({
  baseURL: '/api/v1',
  // IR-COM-03：查询类请求超时 15s；结算等写操作由具体接口单独设置更长超时
  timeout: 15000,
})

// 请求拦截：携带 JWT（IR-SW-03：Authorization: Bearer <token>）
instance.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

instance.interceptors.response.use(
  (response) => {
    // 文件下载（CSV 导出等）直接透传
    if (response.config.responseType === 'blob') {
      return response
    }
    const body = response.data as ApiResponse
    if (body.code === 0) {
      return response
    }
    if (body.code === 401) {
      forceLogout()
      return Promise.reject(new Error(body.message))
    }
    ElMessage.error(body.message || '请求失败')
    return Promise.reject(new Error(body.message))
  },
  (error) => {
    if (error.response?.status === 401) {
      forceLogout()
      return Promise.reject(error)
    }
    const msg =
      error.code === 'ECONNABORTED'
        ? '请求超时，请重试'
        : error.response?.data?.message || '网络异常，请检查与服务器的连接'
    ElMessage.error(msg)
    return Promise.reject(error)
  },
)

/** 401：清除本地登录态并回到登录页（登录接口自身除外） */
function forceLogout() {
  if (router.currentRoute.value.name !== 'login') {
    localStorage.removeItem(TOKEN_KEY)
    router.push({ name: 'login' })
  }
}

/** 轻量封装：自动解包统一响应体，直接返回 data */
export const http = {
  async get<T>(url: string, params?: object): Promise<T> {
    const res = await instance.get<ApiResponse<T>>(url, { params })
    return res.data.data
  },
  async post<T>(url: string, data?: unknown, timeout?: number): Promise<T> {
    const res = await instance.post<ApiResponse<T>>(url, data, { timeout })
    return res.data.data
  },
  async put<T>(url: string, data?: unknown): Promise<T> {
    const res = await instance.put<ApiResponse<T>>(url, data)
    return res.data.data
  },
  async delete<T>(url: string, params?: object): Promise<T> {
    const res = await instance.delete<ApiResponse<T>>(url, { params })
    return res.data.data
  },
  /** 下载文件（CSV/Excel 导出等，blob 响应） */
  async download(url: string, params?: object, config?: AxiosRequestConfig): Promise<Blob> {
    const res = await instance.get(url, {
      params,
      responseType: 'blob',
      ...config,
    })
    return res.data as Blob
  },
}

/** 导出文件保存到本地 */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

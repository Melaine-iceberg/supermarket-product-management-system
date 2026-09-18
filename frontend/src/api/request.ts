import axios from 'axios'

/** 统一响应结构（与后端 IR-SW-01 约定一致） */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  timestamp: string
}

const instance = axios.create({
  baseURL: '/api/v1',
  // IR-COM-03：查询类请求超时 15s；结算等写操作由具体接口单独设置更长超时
  timeout: 15000,
})

instance.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse
    if (body.code === 0) {
      return response
    }
    ElMessage.error(body.message || '请求失败')
    return Promise.reject(new Error(body.message))
  },
  (error) => {
    const msg =
      error.code === 'ECONNABORTED'
        ? '请求超时，请重试'
        : error.response?.data?.message || '网络异常，请检查与服务器的连接'
    ElMessage.error(msg)
    return Promise.reject(error)
  },
)

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
}

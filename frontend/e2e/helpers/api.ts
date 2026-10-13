import type { APIRequestContext } from '@playwright/test'

/** 统一响应结构（IR-SW-01）：{code,message,data,timestamp} */
export interface ApiEnvelope<T = unknown> {
  code: number
  message: string
  data: T
}

/** 分页结果（IR-COM-04） */
export interface PageResult<T> {
  list: T[]
  total: number
}

/** 业务成功码 */
export const CODE_SUCCESS = 0

/** 登录并返回 Token；失败直接抛错（供 global-setup 造数使用） */
export async function apiLogin(
  api: APIRequestContext,
  username: string,
  password: string,
): Promise<string> {
  const res = await api.post('/api/v1/auth/login', { data: { username, password } })
  if (!res.ok()) throw new Error(`登录接口异常：HTTP ${res.status()}（${username}）`)
  const body = (await res.json()) as ApiEnvelope<{ token: string }>
  if (body.code !== CODE_SUCCESS) {
    throw new Error(`登录失败：code=${body.code} ${body.message}（${username}）`)
  }
  return body.data.token
}

type Method = 'get' | 'post' | 'put' | 'delete'

/** 发起请求并同时保留 HTTP 状态与业务响应体（供越权/回归探针断言） */
export async function apiRequest<T = unknown>(
  api: APIRequestContext,
  method: Method,
  url: string,
  opts: { token?: string; data?: unknown; params?: Record<string, unknown> } = {},
): Promise<{ status: number; body: ApiEnvelope<T> }> {
  const res = await api[method](url, {
    headers: opts.token ? { Authorization: `Bearer ${opts.token}` } : undefined,
    data: opts.data,
    params: opts.params,
  })
  return { status: res.status(), body: (await res.json()) as ApiEnvelope<T> }
}

/** 业务码必须为 0 的请求（造数与正向探针使用） */
export async function apiOk<T = unknown>(
  api: APIRequestContext,
  method: Method,
  url: string,
  opts: { token?: string; data?: unknown; params?: Record<string, unknown> } = {},
): Promise<T> {
  const { status, body } = await apiRequest<T>(api, method, url, opts)
  if (status !== 200 || body.code !== CODE_SUCCESS) {
    throw new Error(
      `${method.toUpperCase()} ${url} 失败：HTTP ${status} code=${body.code} ${body.message}`,
    )
  }
  return body.data
}

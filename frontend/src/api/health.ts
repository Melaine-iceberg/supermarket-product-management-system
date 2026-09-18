import { http } from './request'

export interface PingResult {
  app: string
  serverTime: string
}

/** 后端连通性测试 */
export function ping() {
  return http.get<PingResult>('/ping')
}

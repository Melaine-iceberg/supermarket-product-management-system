/** 请求令牌（NFR-REL-02 结算/入库/退货提交防重）：优先 UUID，非安全上下文回退随机串 */
export function newRequestToken(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}-${Math.random().toString(36).slice(2, 10)}`
}

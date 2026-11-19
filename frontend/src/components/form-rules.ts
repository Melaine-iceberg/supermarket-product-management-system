/**
 * 表单规则兼容层：业务规则沿用 Element Plus 的 `trigger` 字段，
 * 但 async-validator 不认识该字段。带额外 key 的 required 规则会把数字值
 * 误判为空（例如 categoryId=2001、retailPrice=20.35 仍提示“必填”）。
 *
 * 这里只剥离交互触发字段，保留 required/message/validator/pattern/type 等标准规则。
 */
export function sanitizeRule(rule: unknown): unknown {
  if (!rule || typeof rule !== 'object') return rule
  const { trigger: _trigger, ...rest } = rule as Record<string, unknown>
  return rest
}

export function sanitizeRules(rules: unknown[]): unknown[] {
  return rules.map(sanitizeRule)
}

/**
 * 金额格式化（IR-UI-05/GAP-P2-25）：千分位 + 固定 2 位小数（成本等需 4 位场景显式传入）。
 * 空值占位符由各调用方保留原有语义；已格式化的字符串直接返回。
 */
export function moneyText(v: number | string, digits = 2): string {
  const n = Number(v)
  if (!Number.isFinite(n)) {
    return String(v ?? '')
  }
  return n.toLocaleString('zh-CN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })
}

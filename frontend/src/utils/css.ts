/**
 * 读取文档根元素上的 CSS 变量。
 * 用于 Canvas/WebGL 等无法解析 `var()` 的场景（如 ECharts 配色）。
 */
export function cssColor(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

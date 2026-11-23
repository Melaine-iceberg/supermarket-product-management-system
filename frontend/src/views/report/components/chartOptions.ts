import type { EChartsOption } from 'echarts'
import { cssColor } from '@/utils/css'

/** 图表配色与坐标轴样式：全部从 Nuxt UI 主题令牌读取，暗色模式随 html.dark 切换 */
export function chartTokens() {
  return {
    primary: cssColor('--ui-primary', '#2563eb'),
    success: cssColor('--ui-success', '#16a34a'),
    warning: cssColor('--ui-warning', '#d97706'),
    error: cssColor('--ui-error', '#dc2626'),
    info: cssColor('--ui-info', '#0284c7'),
    text: cssColor('--ui-text', '#334155'),
    muted: cssColor('--ui-text-muted', '#64748b'),
    border: cssColor('--ui-border', '#e2e8f0'),
    bg: cssColor('--ui-bg', '#ffffff'),
  }
}

export function chartPalette(): string[] {
  const t = chartTokens()
  return [t.primary, t.success, t.warning, t.error, t.info, '#8b5cf6', '#ec4899', '#14b8a6']
}

export function chartBase(): EChartsOption {
  const t = chartTokens()
  return {
    color: chartPalette(),
    textStyle: { color: t.muted },
    animationDuration: 300,
  }
}

export function tooltipCommon(trigger: 'axis' | 'item' = 'axis') {
  const t = chartTokens()
  return {
    trigger,
    backgroundColor: t.bg,
    borderColor: t.border,
    textStyle: { color: t.text },
  }
}

export function legendCommon() {
  const t = chartTokens()
  return {
    textStyle: { color: t.muted },
  }
}

/** 坐标轴通用样式；调用方按需覆盖 splitLine / rotate 等属性 */
export function axisCommon() {
  const t = chartTokens()
  return {
    axisLine: { lineStyle: { color: t.border } },
    axisTick: { lineStyle: { color: t.border } },
    axisLabel: { color: t.muted, fontSize: 11 },
    nameTextStyle: { color: t.muted, fontSize: 11 },
    splitLine: { lineStyle: { color: t.border, type: 'dashed' as const } },
  }
}

/** 金额/数量标签：避免图例与提示框里出现超长小数 */
export function compactNumber(value: number | null | undefined): number {
  return Number(value ?? 0)
}

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RANGE_PRESETS, presetRange } from './rangePresets'

/**
 * 报表快捷区间测试（BR-RPT-04 / GAP-P2-21）：
 * 全部报表统一 今日/昨日/本周/本月/上月/近7日/近30日；默认口径取"今日"。
 */
describe('rangePresets', () => {
  beforeEach(() => {
    // 固定为 2026-06-10（周三），便于断言周/月边界
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 5, 10, 9, 30))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('快捷项顺序与标签固定（覆盖 BR-RPT-04 全部选项）', () => {
    expect(RANGE_PRESETS.map((p) => p.key)).toEqual([
      'today',
      'yesterday',
      'week',
      'month',
      'lastMonth',
      'recent7',
      'recent30',
    ])
    expect(RANGE_PRESETS.map((p) => p.label)).toEqual([
      '今日',
      '昨日',
      '本周',
      '本月',
      '上月',
      '近7日',
      '近30日',
    ])
  })

  it('今日/昨日为单日区间', () => {
    expect(presetRange('today')).toEqual({ beginDate: '2026-06-10', endDate: '2026-06-10' })
    expect(presetRange('yesterday')).toEqual({ beginDate: '2026-06-09', endDate: '2026-06-09' })
  })

  it('本周为周一至今日，本月/上月为自然月边界', () => {
    expect(presetRange('week')).toEqual({ beginDate: '2026-06-08', endDate: '2026-06-10' })
    expect(presetRange('month')).toEqual({ beginDate: '2026-06-01', endDate: '2026-06-10' })
    expect(presetRange('lastMonth')).toEqual({ beginDate: '2026-05-01', endDate: '2026-05-31' })
  })

  it('近 7 日/近 30 日含今日', () => {
    expect(presetRange('recent7')).toEqual({ beginDate: '2026-06-04', endDate: '2026-06-10' })
    expect(presetRange('recent30')).toEqual({ beginDate: '2026-05-12', endDate: '2026-06-10' })
  })
})

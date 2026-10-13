import {
  lastMonthRange,
  recentDaysRange,
  thisMonthRange,
  thisWeekRange,
  todayRange,
  yesterdayRange,
  type ReportRange,
} from '@/api/report'

/**
 * 报表时间区间快捷项（BR-RPT-04 / GAP-P2-21）：
 * 全部报表统一提供 今日/昨日/本周/本月/上月/近7日/近30日，默认区间取"今日"。
 */
export type RangePreset = 'today' | 'yesterday' | 'week' | 'month' | 'lastMonth' | 'recent7' | 'recent30'

export const RANGE_PRESETS: { key: RangePreset; label: string }[] = [
  { key: 'today', label: '今日' },
  { key: 'yesterday', label: '昨日' },
  { key: 'week', label: '本周' },
  { key: 'month', label: '本月' },
  { key: 'lastMonth', label: '上月' },
  { key: 'recent7', label: '近7日' },
  { key: 'recent30', label: '近30日' },
]

/** 快捷区间取值：与 api/report.ts 的日期工具同源，避免各页面重复实现 */
export function presetRange(preset: RangePreset): Required<ReportRange> {
  switch (preset) {
    case 'today':
      return todayRange()
    case 'yesterday':
      return yesterdayRange()
    case 'week':
      return thisWeekRange()
    case 'month':
      return thisMonthRange()
    case 'lastMonth':
      return lastMonthRange()
    case 'recent7':
      return recentDaysRange(7)
    case 'recent30':
      return recentDaysRange(30)
  }
}

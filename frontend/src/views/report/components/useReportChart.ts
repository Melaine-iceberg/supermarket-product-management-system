import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import { nextTick, onBeforeUnmount, onMounted, shallowRef, type Ref } from 'vue'

/**
 * 报表图表生命周期管理：
 * - 视图切换后容器可能被 v-if 重建，render 在 nextTick 后按当前 DOM 重新初始化；
 * - 同时监听窗口尺寸与容器尺寸，保证窄屏/侧栏折叠时自适应；
 * - 深浅色切换时用最新的 option 工厂重新着色；
 * - 卸载时释放实例，避免 ECharts 与 ResizeObserver 泄漏。
 */
export function useReportChart(container: Ref<HTMLDivElement | undefined>) {
  const chart = shallowRef<echarts.ECharts>()
  let optionFactory: (() => EChartsOption) | null = null
  let resizeObserver: ResizeObserver | null = null
  let themeObserver: MutationObserver | null = null

  function dispose() {
    resizeObserver?.disconnect()
    resizeObserver = null
    chart.value?.dispose()
    chart.value = undefined
  }

  function ensure(): echarts.ECharts | null {
    const el = container.value
    if (!el) return null
    const current = chart.value
    if (current && (current.getDom() !== el || !current.getDom().isConnected)) {
      dispose()
    }
    if (!chart.value) {
      chart.value = echarts.init(el)
      if (typeof ResizeObserver !== 'undefined') {
        resizeObserver = new ResizeObserver(() => chart.value?.resize())
        resizeObserver.observe(el)
      }
    }
    return chart.value
  }

  /** 用 option 工厂而不是固定对象，切换深浅色时可以重新读取 CSS 令牌 */
  async function render(factory: () => EChartsOption) {
    optionFactory = factory
    await nextTick()
    const instance = ensure()
    if (!instance) return
    instance.setOption(factory(), true)
    instance.resize()
  }

  function handleResize() {
    chart.value?.resize()
  }

  function handleThemeChange() {
    if (!chart.value || !optionFactory) return
    chart.value.setOption(optionFactory(), true)
  }

  onMounted(() => {
    window.addEventListener('resize', handleResize)
    themeObserver = new MutationObserver(handleThemeChange)
    themeObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class'],
    })
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', handleResize)
    themeObserver?.disconnect()
    themeObserver = null
    dispose()
  })

  return { chart, render, dispose }
}

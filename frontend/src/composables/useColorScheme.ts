import { computed, ref, type ComputedRef, type Ref } from 'vue'

export type ColorSchemePreference = 'light' | 'dark' | null
export type ResolvedColorScheme = 'light' | 'dark'

const STORAGE_KEY = 'smms-color-scheme'
const DARK_QUERY = '(prefers-color-scheme: dark)'

function getMediaQuery(): MediaQueryList | null {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(DARK_QUERY)
    : null
}

function readPreference(): ColorSchemePreference {
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY)
    return saved === 'light' || saved === 'dark' ? saved : null
  } catch {
    // 站点数据被禁用时按跟随系统处理
    return null
  }
}

const media = getMediaQuery()
const preference = ref<ColorSchemePreference>(readPreference())
const systemScheme = ref<ResolvedColorScheme>(media?.matches ? 'dark' : 'light')
const resolved = computed<ResolvedColorScheme>(() => preference.value ?? systemScheme.value)

let initialized = false

/**
 * 将主题同步到两个地方：
 * 1. `<meta name="color-scheme">`：驱动 CSS `color-scheme` 与 light-dark()；
 * 2. `html.dark`：驱动 Nuxt UI / Tailwind 的暗色变体。
 */
function applyColorScheme() {
  if (typeof document === 'undefined') return

  document.documentElement.classList.toggle('dark', resolved.value === 'dark')

  const meta = document.querySelector<HTMLMetaElement>('meta[name="color-scheme"]')
  if (meta) meta.content = preference.value ?? 'light dark'
}

function writePreference(next: ColorSchemePreference) {
  preference.value = next
  try {
    if (next) window.localStorage.setItem(STORAGE_KEY, next)
    else window.localStorage.removeItem(STORAGE_KEY)
  } catch {
    // 无法持久化时主题仍在当前页面生效
  }
}

/** 切换到当前渲染方案的反面；与系统偏好一致时撤销覆盖，重新跟随系统。 */
function toggle() {
  const target: ResolvedColorScheme = resolved.value === 'dark' ? 'light' : 'dark'
  writePreference(target === systemScheme.value ? null : target)
  applyColorScheme()
}

function init() {
  if (initialized) return
  initialized = true

  // 内联脚本已在首屏前应用过一次，这里只补充运行时监听
  applyColorScheme()

  media?.addEventListener('change', (event) => {
    systemScheme.value = event.matches ? 'dark' : 'light'
    // 手动固定方案后不再跟随系统变化
    if (!preference.value) applyColorScheme()
  })

  window.addEventListener('storage', (event) => {
    if (event.key !== STORAGE_KEY) return
    preference.value = readPreference()
    applyColorScheme()
  })
}

export function useColorScheme(): {
  preference: Ref<ColorSchemePreference>
  resolved: ComputedRef<ResolvedColorScheme>
  toggle: () => void
} {
  init()
  return { preference, resolved, toggle }
}

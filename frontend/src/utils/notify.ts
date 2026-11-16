import type { useToast } from '@nuxt/ui/composables'

export type NotifyType = 'success' | 'error' | 'warning' | 'info'

/**
 * 全局消息提示（替代 Element Plus 的 ElMessage）。
 *
 * Nuxt UI 的 useToast 只能在组件 setup 中获取，而接口拦截器、异步任务等
 * 非组件代码也需要提示，因此由 App.vue 内的 ToastBridge 绑定实例，
 * 绑定前的消息先进入队列，绑定后统一回放。
 */
type ToastApi = ReturnType<typeof useToast>
type ToastInput = Parameters<ToastApi['add']>[0]

const ICONS: Record<NotifyType, string> = {
  success: 'i-lucide-circle-check',
  error: 'i-lucide-circle-x',
  warning: 'i-lucide-triangle-alert',
  info: 'i-lucide-info',
}

const COLORS: Record<NotifyType, ToastInput['color']> = {
  success: 'success',
  error: 'error',
  warning: 'warning',
  info: 'info',
}

let api: ToastApi | null = null
const pending: ToastInput[] = []

function push(message: string, type: NotifyType, duration = 3000) {
  const toast: ToastInput = {
    title: message,
    color: COLORS[type],
    icon: ICONS[type],
    duration,
    ui: { root: 'app-toast' },
  }
  if (api) api.add(toast)
  else pending.push(toast)
}

export function bindToast(toastApi: ToastApi) {
  api = toastApi
  while (pending.length > 0) {
    const toast = pending.shift()
    if (toast) api.add(toast)
  }
}

export const notify = {
  success(message: string, duration?: number) {
    push(message, 'success', duration)
  },
  error(message: string, duration?: number) {
    push(message, 'error', duration)
  },
  warning(message: string, duration?: number) {
    push(message, 'warning', duration)
  },
  info(message: string, duration?: number) {
    push(message, 'info', duration)
  },
}

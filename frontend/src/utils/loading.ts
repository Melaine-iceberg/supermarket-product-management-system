import { reactive } from 'vue'

/**
 * 全局加载浮层（替代 Element Plus 的 ElLoading.service）。
 * 异步任务轮询等非组件代码可调用 showLoading() 获取可更新文案的句柄。
 */
export const loadingState = reactive({
  count: 0,
  text: '',
})

export interface LoadingHandle {
  setText: (text: string) => void
  close: () => void
}

export function showLoading(text = '处理中…'): LoadingHandle {
  loadingState.count += 1
  loadingState.text = text
  let closed = false
  return {
    setText(next: string) {
      if (!closed) loadingState.text = next
    },
    close() {
      if (closed) return
      closed = true
      loadingState.count = Math.max(0, loadingState.count - 1)
      if (loadingState.count === 0) loadingState.text = ''
    },
  }
}

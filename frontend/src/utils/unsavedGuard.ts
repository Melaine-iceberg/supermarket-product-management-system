import { onMounted, onUnmounted } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'

/**
 * 表单未保存离开提醒（IR-UI-07/GAP-P2-25）：
 * - 路由内跳转：isDirty() 为真时弹确认框，选择"留在本页"则取消导航；
 * - 浏览器刷新/关闭：注册 beforeunload 原生提示。
 * 用法：在表单页面 setup 中调用，isDirty 返回当前是否存在未保存修改。
 */
export function useUnsavedGuard(
  isDirty: () => boolean,
  message = '当前表单有未保存的修改，确认离开？',
) {
  onBeforeRouteLeave(async () => {
    if (!isDirty()) return true
    try {
      await ElMessageBox.confirm(message, '未保存提示', {
        type: 'warning',
        confirmButtonText: '离开',
        cancelButtonText: '留在本页',
      })
      return true
    } catch {
      return false
    }
  })

  const beforeUnload = (e: BeforeUnloadEvent) => {
    if (!isDirty()) return
    e.preventDefault()
    // 兼容旧浏览器：设置 returnValue 才会触发原生确认
    e.returnValue = ''
  }
  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))
}

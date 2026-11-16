import { reactive } from 'vue'

export type ConfirmMode = 'confirm' | 'prompt' | 'alert'

export interface ConfirmOptions {
  type?: 'success' | 'warning' | 'info' | 'error'
  confirmButtonText?: string
  cancelButtonText?: string
  inputPlaceholder?: string
  inputValue?: string
  inputType?: 'text' | 'password'
  inputPattern?: RegExp
  inputErrorMessage?: string
  inputValidator?: (value: string) => boolean | string
  closeOnClickModal?: boolean
  closeOnPressEscape?: boolean
}

interface ConfirmState {
  open: boolean
  mode: ConfirmMode
  title: string
  message: string
  options: ConfirmOptions
  input: string
  error: string
}

export const confirmState = reactive<ConfirmState>({
  open: false,
  mode: 'confirm',
  title: '提示',
  message: '',
  options: {},
  input: '',
  error: '',
})

let pending:
  | {
      resolve: (value: unknown) => void
      reject: (reason?: unknown) => void
      mode: ConfirmMode
    }
  | null = null

function openDialog(
  mode: ConfirmMode,
  message: string,
  title: string,
  options: ConfirmOptions,
): Promise<unknown> {
  // 上一次弹窗尚未结束时直接取消，避免 Promise 悬挂
  if (pending) settle(false)

  Object.assign(confirmState, {
    open: true,
    mode,
    title,
    message,
    options,
    input: options.inputValue ?? '',
    error: '',
  })

  return new Promise((resolve, reject) => {
    pending = { resolve, reject, mode }
  })
}

/** 确认框：确认返回 true，取消以 'cancel' 拒绝（与 ElMessageBox 行为一致） */
export function confirm(
  message: string,
  title = '提示',
  options: ConfirmOptions = {},
): Promise<boolean> {
  return openDialog('confirm', message, title, options) as Promise<boolean>
}

/** 输入框：确认返回 { value }，取消以 'cancel' 拒绝 */
export function prompt(
  message: string,
  title = '提示',
  options: ConfirmOptions = {},
): Promise<{ value: string }> {
  return openDialog('prompt', message, title, options) as Promise<{ value: string }>
}

/** 提示框：只有一个确认按钮 */
export function alert(message: string, title = '提示', options: ConfirmOptions = {}): Promise<void> {
  return openDialog('alert', message, title, options) as Promise<void>
}

function validateInput(): boolean {
  const { options, input } = confirmState
  if (confirmState.mode !== 'prompt') return true
  if (options.inputPattern && !options.inputPattern.test(input)) {
    confirmState.error = options.inputErrorMessage || '输入格式不正确'
    return false
  }
  if (options.inputValidator) {
    const result = options.inputValidator(input)
    if (result !== true) {
      confirmState.error = typeof result === 'string' ? result : '输入内容不合法'
      return false
    }
  }
  confirmState.error = ''
  return true
}

/** 由 ConfirmDialog 调用：确认当前弹窗 */
export function acceptConfirm() {
  if (!pending) return
  if (!validateInput()) return
  const input = confirmState.input
  if (pending.mode === 'alert') settle(undefined)
  else if (pending.mode === 'prompt') settle({ value: input })
  else settle(true)
}

/** 由 ConfirmDialog 调用：取消/关闭当前弹窗 */
export function cancelConfirm() {
  settle(false)
}

function settle(value: unknown) {
  const current = pending
  if (!current) {
    confirmState.open = false
    return
  }
  pending = null
  confirmState.open = false
  if (value === false) current.reject('cancel')
  else current.resolve(value)
}

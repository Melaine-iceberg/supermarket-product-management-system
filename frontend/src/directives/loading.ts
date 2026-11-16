import type { Directive } from 'vue'

/**
 * v-loading 兼容指令：为任意容器加半透明遮罩与旋转指示器。
 * 表格请优先使用 AppTable 的 loading 属性；该指令用于详情面板等局部区域。
 */
const masks = new WeakMap<HTMLElement, HTMLDivElement>()

function ensureMask(el: HTMLElement): HTMLDivElement {
  const existing = masks.get(el)
  if (existing) return existing

  const mask = document.createElement('div')
  mask.className = 'smms-loading-mask'
  mask.setAttribute('role', 'status')
  mask.setAttribute('aria-live', 'polite')
  mask.innerHTML = '<span class="smms-loading-spinner" aria-hidden="true"></span>'
  el.appendChild(mask)
  masks.set(el, mask)
  return mask
}

function update(el: HTMLElement, value: boolean) {
  if (value) {
    if (getComputedStyle(el).position === 'static') el.style.position = 'relative'
    ensureMask(el).style.display = 'grid'
  } else {
    const mask = masks.get(el)
    if (mask) mask.style.display = 'none'
  }
}

export const vLoading: Directive<HTMLElement, boolean> = {
  mounted(el, binding) {
    update(el, !!binding.value)
  },
  updated(el, binding) {
    update(el, !!binding.value)
  },
  unmounted(el) {
    masks.get(el)?.remove()
    masks.delete(el)
  },
}

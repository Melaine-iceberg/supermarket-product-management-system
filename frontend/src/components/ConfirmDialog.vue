<script setup lang="ts">
import { computed } from 'vue'
import { acceptConfirm, cancelConfirm, confirmState } from '@/utils/confirm'

const confirmColor = computed(() => {
  switch (confirmState.options.type) {
    case 'error':
      return 'error'
    case 'warning':
      return 'warning'
    case 'success':
      return 'success'
    case 'info':
      return 'info'
    default:
      return 'primary'
  }
})

const confirmText = computed(
  () => confirmState.options.confirmButtonText ?? (confirmState.mode === 'alert' ? '知道了' : '确定'),
)
const cancelText = computed(() => confirmState.options.cancelButtonText ?? '取消')

function onOpenChange(open: boolean) {
  // 点击遮罩/关闭按钮/按 Esc 关闭时，等价于取消
  if (!open) cancelConfirm()
}
</script>

<template>
  <UModal
    v-model:open="confirmState.open"
    :title="confirmState.title"
    :dismissible="
      confirmState.options.closeOnClickModal !== false &&
      confirmState.options.closeOnPressEscape !== false
    "
    :ui="{ footer: 'justify-end' }"
    @update:open="onOpenChange"
  >
    <template #body>
      <p class="whitespace-pre-line text-sm leading-relaxed text-muted">
        {{ confirmState.message }}
      </p>
      <div v-if="confirmState.mode === 'prompt'" class="mt-4">
        <UInput
          v-model="confirmState.input"
          :type="confirmState.options.inputType || 'text'"
          :placeholder="confirmState.options.inputPlaceholder"
          class="w-full"
          autofocus
          @keyup.enter="acceptConfirm"
        />
        <p v-if="confirmState.error" class="mt-1 text-xs text-error">{{ confirmState.error }}</p>
      </div>
    </template>

    <template #footer>
      <UButton
        v-if="confirmState.mode !== 'alert'"
        color="neutral"
        variant="outline"
        :label="cancelText"
        @click="cancelConfirm"
      />
      <UButton :color="confirmColor" :label="confirmText" @click="acceptConfirm" />
    </template>
  </UModal>
</template>

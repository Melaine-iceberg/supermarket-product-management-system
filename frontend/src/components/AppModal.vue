<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: boolean
    title?: string
    width?: string | number
    fullscreen?: boolean
    showClose?: boolean
    closeOnClickModal?: boolean
    closeOnPressEscape?: boolean
  }>(),
  {
    modelValue: false,
    title: '',
    width: '',
    fullscreen: false,
    showClose: true,
    closeOnClickModal: true,
    closeOnPressEscape: true,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  open: []
  opened: []
  close: []
  closed: []
}>()

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const contentProps = computed<any>(() => {
  if (!props.width) return undefined
  const width = typeof props.width === 'number' ? `${props.width}px` : props.width
  return { style: { maxWidth: width } }
})

const dismissible = computed(
  () => props.closeOnClickModal !== false && props.closeOnPressEscape !== false,
)

function onOpenChange(value: boolean) {
  emit('update:modelValue', value)
  if (value) emit('open')
  else emit('close')
}
</script>

<template>
  <UModal
    v-model:open="open"
    :title="title"
    :fullscreen="fullscreen"
    :dismissible="dismissible"
    :close="showClose === false ? false : undefined"
    :content="contentProps"
    @update:open="onOpenChange"
    @after:enter="emit('opened')"
    @after:leave="emit('closed')"
  >
    <template #body>
      <slot name="body">
        <slot />
      </slot>
    </template>
    <template v-if="$slots.header" #header>
      <slot name="header" />
    </template>
    <template v-if="$slots.footer" #footer>
      <slot name="footer" />
    </template>
  </UModal>
</template>

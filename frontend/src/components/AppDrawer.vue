<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: boolean
    title?: string
    direction?: 'ltr' | 'rtl' | 'ttb' | 'btt'
    size?: string | number
    withHeader?: boolean
    showClose?: boolean
    closeOnClickModal?: boolean
    closeOnPressEscape?: boolean
  }>(),
  {
    modelValue: false,
    title: '',
    direction: 'rtl',
    size: '30%',
    withHeader: true,
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

const side = computed(() => {
  switch (props.direction) {
    case 'ltr':
      return 'left'
    case 'ttb':
      return 'top'
    case 'btt':
      return 'bottom'
    default:
      return 'right'
  }
})

const contentProps = computed<any>(() => {
  if (!props.size) return undefined
  const size = typeof props.size === 'number' ? `${props.size}px` : props.size
  return {
    style: side.value === 'left' || side.value === 'right' ? { width: size } : { height: size },
  }
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
  <USlideover
    v-model:open="open"
    :title="withHeader ? title : undefined"
    :side="side"
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
  </USlideover>
</template>

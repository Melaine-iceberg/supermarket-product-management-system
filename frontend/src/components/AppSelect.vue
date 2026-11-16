<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import { Comment, Fragment, computed, useSlots, type VNode } from 'vue'
import AppOption from './AppOption.vue'

defineOptions({ inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    modelValue?: any
    multiple?: boolean
    filterable?: boolean
    remote?: boolean
    remoteMethod?: (query: string) => void
    clearable?: boolean
    placeholder?: string
    disabled?: boolean
    size?: 'large' | 'default' | 'small'
    loading?: boolean
  }>(),
  {
    modelValue: undefined,
    multiple: false,
    filterable: false,
    remote: false,
    remoteMethod: undefined,
    clearable: false,
    placeholder: '请选择',
    disabled: false,
    size: 'default',
    loading: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: any]
  change: [value: any]
  'visible-change': [visible: boolean]
  clear: []
}>()

const slots = useSlots()

function flatten(nodes: unknown[]): VNode[] {
  const result: VNode[] = []
  for (const node of nodes) {
    if (!node) continue
    if (Array.isArray(node)) {
      result.push(...flatten(node))
      continue
    }
    const vnode = node as VNode
    if (vnode.type === Comment) continue
    if (vnode.type === Fragment && Array.isArray(vnode.children)) {
      result.push(...flatten(vnode.children as unknown[]))
      continue
    }
    result.push(vnode)
  }
  return result
}

function isOptionNode(node: VNode): boolean {
  const type = node.type as { __name?: string; name?: string } | null
  return node.type === AppOption || type?.__name === 'AppOption' || type?.name === 'AppOption'
}

function textOf(value: unknown): string {
  if (value == null || typeof value === 'boolean') return ''
  if (typeof value === 'string' || typeof value === 'number') return String(value)
  if (Array.isArray(value)) return value.map(textOf).join('')
  const vnode = value as VNode
  const children = vnode.children
  if (children == null) return ''
  if (typeof children === 'string') return children
  if (Array.isArray(children)) return children.map(textOf).join('')
  const childSlots = children as Record<string, () => unknown>
  return childSlots.default ? textOf(childSlots.default()) : ''
}

const items = computed(() => {
  const nodes = flatten((slots.default?.() ?? []) as unknown[]).filter(isOptionNode)
  return nodes.map((node) => {
    const option = (node.props ?? {}) as Record<string, any>
    return {
      label: option.label || textOf(node) || String(option.value ?? ''),
      value: option.value,
      disabled: !!option.disabled,
    }
  })
})

const model = computed({
  get: () => props.modelValue,
  set: (value: any) => {
    emit('update:modelValue', value)
    emit('change', value)
  },
})

const mappedSize = computed(() => {
  if (props.size === 'large') return 'lg'
  if (props.size === 'small') return 'sm'
  return 'md'
})

const useMenu = computed(
  () => props.filterable || props.remote || props.clearable || props.multiple,
)

function onSearch(term: string) {
  if (props.remote) props.remoteMethod?.(term)
}

function onOpen(open: boolean) {
  emit('visible-change', open)
  if (open && props.remote) props.remoteMethod?.('')
}
</script>

<template>
  <USelectMenu
    v-if="useMenu"
    v-model="model"
    v-bind="$attrs"
    :items="items"
    value-key="value"
    :multiple="multiple"
    :placeholder="placeholder"
    :disabled="disabled"
    :size="mappedSize"
    :search-input="filterable || remote ? { placeholder: '搜索…' } : false"
    :clear="clearable ? true : undefined"
    @update:search-term="onSearch"
    @update:open="onOpen"
    @clear="emit('clear')"
  />
  <USelect
    v-else
    v-model="model"
    v-bind="$attrs"
    :items="items"
    value-key="value"
    :placeholder="placeholder"
    :disabled="disabled"
    :size="mappedSize"
  />
</template>

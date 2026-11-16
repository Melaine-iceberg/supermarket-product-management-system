<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import { Comment, Fragment, computed, useSlots, type VNode } from 'vue'
import AppRadio from './AppRadio.vue'
import AppRadioButton from './AppRadioButton.vue'

const props = withDefaults(
  defineProps<{
    modelValue?: any
    disabled?: boolean
    size?: 'large' | 'default' | 'small'
    button?: boolean
  }>(),
  {
    modelValue: undefined,
    disabled: false,
    size: 'default',
    button: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: any]
  change: [value: any]
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

function isRadioNode(node: VNode): boolean {
  const type = node.type as { __name?: string; name?: string } | null
  return (
    node.type === AppRadio ||
    node.type === AppRadioButton ||
    type?.__name === 'AppRadio' ||
    type?.__name === 'AppRadioButton' ||
    type?.name === 'AppRadio' ||
    type?.name === 'AppRadioButton'
  )
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
  const nodes = flatten((slots.default?.() ?? []) as unknown[]).filter(isRadioNode)
  return nodes.map((node) => {
    const radio = (node.props ?? {}) as Record<string, any>
    return {
      label: radio.label || textOf(node) || String(radio.value ?? ''),
      value: radio.value,
      disabled: !!radio.disabled,
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

const isButton = computed(
  () =>
    props.button ||
    flatten((slots.default?.() ?? []) as unknown[]).some((node) => {
      const type = node.type as { __name?: string; name?: string } | null
      return type?.__name === 'AppRadioButton' || type?.name === 'AppRadioButton'
    }),
)

const mappedSize = computed(() => {
  if (props.size === 'large') return 'lg'
  if (props.size === 'small') return 'sm'
  return 'md'
})
</script>

<template>
  <URadioGroup
    v-model="model"
    :items="items"
    :disabled="disabled"
    :size="mappedSize"
    :variant="isButton ? 'card' : 'list'"
    orientation="horizontal"
  />
</template>

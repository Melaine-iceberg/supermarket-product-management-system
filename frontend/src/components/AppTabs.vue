<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import { Comment, Fragment, computed, useSlots, type VNode } from 'vue'
import AppTabPane from './AppTabPane.vue'

const props = withDefaults(
  defineProps<{
    modelValue?: string | number
    type?: string
    tabPosition?: string
    stretch?: boolean
    closable?: boolean
    editable?: boolean
  }>(),
  {
    modelValue: undefined,
    type: '',
    tabPosition: 'top',
    stretch: false,
    closable: false,
    editable: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string | number]
  'tab-change': [value: string | number]
  'tab-click': [pane: { name: string | number; label: string }]
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

function isPaneNode(node: VNode): boolean {
  const type = node.type as { __name?: string; name?: string } | null
  return (
    node.type === AppTabPane ||
    type?.__name === 'AppTabPane' ||
    type?.name === 'AppTabPane'
  )
}

const panes = computed(() => {
  const nodes = flatten((slots.default?.() ?? []) as unknown[]).filter(isPaneNode)
  return nodes.map((node, index) => {
    const pane = (node.props ?? {}) as Record<string, any>
    const childSlots = (node.children ?? {}) as Record<string, (params: any) => unknown>
    return {
      label: pane.label || `标签 ${index + 1}`,
      name: (pane.name as string | number) ?? `pane_${index}`,
      disabled: !!pane.disabled,
      slot: childSlots.default ?? null,
    }
  })
})

const items = computed(() =>
  panes.value.map((pane) => ({
    label: pane.label,
    value: pane.name,
    disabled: pane.disabled,
  })),
)

const active = computed({
  get: () => props.modelValue ?? panes.value[0]?.name ?? '',
  set: (value: string | number) => {
    if (value === active.value) return
    emit('update:modelValue', value)
    emit('tab-change', value)
    const pane = panes.value.find((item) => item.name === value)
    if (pane) emit('tab-click', { name: pane.name, label: pane.label })
  },
})

const activeSlot = computed(
  () => panes.value.find((pane) => pane.name === active.value)?.slot ?? null,
)

const RenderSlot = (renderProps: { fn?: ((params: any) => unknown) | null; params?: any }) =>
  renderProps.fn?.(renderProps.params) ?? null
</script>

<template>
  <div class="app-tabs">
    <UTabs
      v-model="active"
      :items="items"
      :content="false"
      variant="link"
      color="primary"
    />
    <div class="mt-4">
      <RenderSlot :fn="activeSlot" :params="{}" />
    </div>
  </div>
</template>

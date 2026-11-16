<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import { Comment, Fragment, computed, useSlots, type VNode } from 'vue'
import AppDescriptionsItem from './AppDescriptionsItem.vue'

const props = withDefaults(
  defineProps<{
    column?: number
    border?: boolean
    direction?: 'horizontal' | 'vertical'
    size?: 'large' | 'default' | 'small'
    title?: string
    extra?: string
  }>(),
  {
    column: 3,
    border: false,
    direction: 'horizontal',
    size: 'default',
    title: '',
    extra: '',
  },
)

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

function isItemNode(node: VNode): boolean {
  const type = node.type as { __name?: string; name?: string } | null
  return (
    node.type === AppDescriptionsItem ||
    type?.__name === 'AppDescriptionsItem' ||
    type?.name === 'AppDescriptionsItem'
  )
}

const items = computed(() => {
  const nodes = flatten((slots.default?.() ?? []) as unknown[]).filter(isItemNode)
  return nodes.map((node) => {
    const item = (node.props ?? {}) as Record<string, any>
    const childSlots = (node.children ?? {}) as Record<string, (params: any) => unknown>
    return {
      label: item.label ?? '',
      span: item.span ?? 1,
      slot: childSlots.default ?? null,
      width: item.width,
      labelWidth: item.labelWidth,
    }
  })
})

const RenderSlot = (renderProps: { fn?: ((params: any) => unknown) | null; params?: any }) =>
  renderProps.fn?.(renderProps.params) ?? null

const gridStyle = computed(() => ({
  '--app-desc-columns': String(props.column || 1),
  '--app-desc-border': props.border ? '1px' : '0px',
}))
</script>

<template>
  <div class="app-descriptions" :style="gridStyle">
    <div v-if="title || extra" class="app-desc-head">
      <span class="font-medium text-default">{{ title }}</span>
      <span class="text-sm text-muted">{{ extra }}</span>
    </div>
    <dl class="app-desc-grid" :class="{ 'app-desc-bordered': border }">
      <div
        v-for="(item, index) in items"
        :key="index"
        class="app-desc-item"
        :style="{
          gridColumn: item.span > 1 ? `span ${item.span}` : undefined,
          width: item.width,
        }"
      >
        <dt class="app-desc-label" :style="{ width: item.labelWidth }">{{ item.label }}</dt>
        <dd class="app-desc-value">
          <RenderSlot :fn="item.slot" :params="{}" />
        </dd>
      </div>
    </dl>
  </div>
</template>

<style scoped>
.app-desc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.app-desc-grid {
  display: grid;
  grid-template-columns: repeat(var(--app-desc-columns, 3), minmax(0, 1fr));
  gap: 8px 20px;
  margin: 0;
}

.app-desc-bordered {
  gap: 0;
  overflow: hidden;
  border: 1px solid var(--ui-border);
  border-radius: 8px;
}

.app-desc-item {
  display: flex;
  gap: 8px;
  min-width: 0;
  font-size: 14px;
  line-height: 1.5;
}

.app-desc-label {
  flex-shrink: 0;
  color: var(--ui-text-muted);
}

.app-desc-bordered .app-desc-item {
  padding: 10px 12px;
  border-right: 1px solid var(--ui-border);
  border-bottom: 1px solid var(--ui-border);
}

.app-desc-value {
  min-width: 0;
  margin: 0;
  color: var(--ui-text);
}

@media (max-width: 768px) {
  .app-desc-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import { computed, ref } from 'vue'
import { useFormField } from '@nuxt/ui/composables/useFormField'

defineOptions({ inheritAttrs: false })

interface TreeNode {
  label: string
  value: string | number
  disabled?: boolean
  children?: TreeNode[]
}

const props = withDefaults(
  defineProps<{
    modelValue?: string | number | Array<string | number> | null
    data?: any[]
    props?: { label?: string; children?: string; value?: string; disabled?: string }
    nodeKey?: string
    multiple?: boolean
    clearable?: boolean
    filterable?: boolean
    placeholder?: string
    disabled?: boolean
    size?: 'large' | 'default' | 'small'
    /**
     * 默认展开所有含子节点的分类。
     * 商品表单会把非叶子分类置为 disabled（不可选），而 UTree 的 disabled 会连展开箭头一起禁用；
     * 开启后叶子节点直接可见，避免“分类树只能看到一级、无法选择”的问题。
     */
    expandAll?: boolean
  }>(),
  {
    modelValue: null,
    data: () => [],
    props: () => ({}),
    nodeKey: 'id',
    multiple: false,
    clearable: true,
    filterable: true,
    placeholder: '请选择',
    disabled: false,
    size: 'default',
    expandAll: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string | number | Array<string | number> | null]
  change: [value: string | number | Array<string | number> | null]
  'visible-change': [visible: boolean]
}>()

/**
 * 树选择不是单一原生输入，必须主动消费 UFormField 注入的 id，
 * 否则 <label for="..."> 会指向不存在的元素（可访问性告警，屏幕阅读器也无法朗读字段名）。
 */
const { id: controlId, labelId, ariaAttrs } = useFormField()

const open = ref(false)
const search = ref('')

function normalize(nodes: any[]): TreeNode[] {
  return (nodes ?? []).map((node) => {
    const label = node[props.props?.label ?? 'label'] ?? node.label ?? node.name ?? ''
    const value = node[props.props?.value ?? props.nodeKey] ?? node.id ?? node.value ?? label
    const children = node[props.props?.children ?? 'children'] ?? node.children
    return {
      label: String(label),
      value,
      disabled: !!node[props.props?.disabled ?? 'disabled'],
      children: Array.isArray(children) && children.length ? normalize(children) : undefined,
    }
  })
}

const tree = computed(() => normalize(props.data))

function flatten(nodes: TreeNode[]): TreeNode[] {
  const result: TreeNode[] = []
  for (const node of nodes) {
    result.push(node)
    if (node.children) result.push(...flatten(node.children))
  }
  return result
}

const flatTree = computed(() => flatten(tree.value))

const filteredTree = computed(() => {
  const query = search.value.trim().toLowerCase()
  if (!query) return tree.value
  const filter = (nodes: TreeNode[]): TreeNode[] =>
    nodes
      .map((node) => {
        const matched = node.label.toLowerCase().includes(query)
        const children = node.children ? filter(node.children) : []
        if (matched) return node
        if (children.length) return { ...node, children }
        return null
      })
      .filter((node): node is TreeNode => node !== null)
  return filter(tree.value)
})

const selectedValues = computed<Array<string | number>>(() => {
  const value = props.modelValue
  if (Array.isArray(value)) return value
  if (value === null || value === undefined || value === '') return []
  return [value]
})

const selectedLabel = computed(() =>
  selectedValues.value
    .map((value) => flatTree.value.find((node) => node.value === value)?.label)
    .filter(Boolean)
    .join('、'),
)

const selectedNodes = computed(() => {
  const found = selectedValues.value
    .map((value) => flatTree.value.find((node) => node.value === value))
    .filter((node): node is TreeNode => !!node)
  return props.multiple ? found : found[0]
})

const mappedSize = computed(() => {
  if (props.size === 'large') return 'lg'
  if (props.size === 'small') return 'sm'
  return 'md'
})

function emitValue(value: string | number | Array<string | number> | null) {
  emit('update:modelValue', value)
  emit('change', value)
}

function onTreeUpdate(value: TreeNode | TreeNode[] | undefined) {
  if (props.multiple) {
    const nodes = Array.isArray(value) ? value : value ? [value] : []
    emitValue(nodes.map((node) => node.value))
  } else {
    emitValue(Array.isArray(value) ? (value[0]?.value ?? null) : (value?.value ?? null))
  }
  open.value = false
}

function clear() {
  emitValue(props.multiple ? [] : null)
}

const getKey = (node: TreeNode) => String(node.value)

/** 展开所有含子节点的节点，保证被 disabled 的父分类不会挡住叶子选择 */
function collectExpandableKeys(nodes: TreeNode[]): string[] {
  const keys: string[] = []
  for (const node of nodes) {
    if (node.children?.length) {
      keys.push(getKey(node))
      keys.push(...collectExpandableKeys(node.children))
    }
  }
  return keys
}

const defaultExpandedKeys = computed(() =>
  props.expandAll ? collectExpandableKeys(tree.value) : undefined,
)
</script>

<template>
  <div class="flex w-full items-center gap-1" v-bind="$attrs">
    <UPopover
      v-model:open="open"
      :content="{ align: 'start' }"
      class="min-w-0 flex-1"
      @update:open="emit('visible-change', $event)"
    >
      <UButton
        :id="controlId"
        :aria-labelledby="labelId ? `${labelId} ${controlId}` : undefined"
        v-bind="ariaAttrs"
        color="neutral"
        variant="outline"
        :disabled="disabled"
        :size="mappedSize"
        class="w-full justify-between"
      >
        <span class="truncate" :class="{ 'text-dimmed': !selectedLabel }">
          {{ selectedLabel || placeholder }}
        </span>
        <UIcon name="i-lucide-chevron-down" class="size-4 shrink-0 text-dimmed" />
      </UButton>

      <template #content>
        <div class="w-72 max-w-[calc(100vw-2rem)] p-2">
          <UInput
            v-if="filterable"
            v-model="search"
            icon="i-lucide-search"
            placeholder="搜索…"
            size="sm"
            class="w-full"
          />
          <div class="mt-2 max-h-72 overflow-auto">
            <UTree
              v-if="filteredTree.length"
              :items="filteredTree"
              label-key="label"
              :get-key="getKey"
              :model-value="selectedNodes"
              :multiple="multiple"
              :default-expanded="defaultExpandedKeys"
              @update:model-value="onTreeUpdate"
            />
            <p v-else class="py-6 text-center text-sm text-muted">暂无匹配项</p>
          </div>
        </div>
      </template>
    </UPopover>

    <UButton
      v-if="clearable && selectedValues.length && !disabled"
      icon="i-lucide-x"
      color="neutral"
      variant="ghost"
      size="sm"
      square
      aria-label="清空选择"
      @click="clear"
    />
  </div>
</template>

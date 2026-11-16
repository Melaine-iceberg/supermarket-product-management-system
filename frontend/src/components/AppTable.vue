<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import {
  Comment,
  Fragment,
  computed,
  h,
  nextTick,
  ref,
  useSlots,
  watch,
  type VNode,
} from 'vue'
import type { TableColumn } from '@nuxt/ui'
import UButton from '@nuxt/ui/components/Button.vue'
import UCheckbox from '@nuxt/ui/components/Checkbox.vue'
import AppTableColumn from './AppTableColumn.vue'

const props = withDefaults(
  defineProps<{
    data?: any[]
    loading?: boolean
    border?: boolean
    stripe?: boolean
    size?: 'large' | 'default' | 'small'
    maxHeight?: string | number
    rowKey?: string
    emptyText?: string
    highlightCurrentRow?: boolean
    showOverflowTooltip?: boolean
  }>(),
  {
    data: () => [],
    loading: false,
    border: false,
    stripe: false,
    size: 'default',
    maxHeight: '',
    rowKey: 'id',
    emptyText: '暂无数据',
    highlightCurrentRow: false,
    showOverflowTooltip: false,
  },
)

const emit = defineEmits<{
  'selection-change': [rows: any[]]
  'sort-change': [payload: { prop: string | null; order: 'ascending' | 'descending' | null }]
  'row-click': [row: any]
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

function isColumnNode(node: VNode): boolean {
  const type = node.type as { __name?: string; name?: string } | null
  return (
    node.type === AppTableColumn ||
    type?.__name === 'AppTableColumn' ||
    type?.name === 'AppTableColumn'
  )
}

function getByPath(row: any, path: string): unknown {
  if (!path) return undefined
  return path.split('.').reduce<any>((value, key) => (value == null ? value : value[key]), row)
}

function toCssSize(value: string | number | undefined): string | undefined {
  if (value === undefined || value === '') return undefined
  return typeof value === 'number' ? `${value}px` : value
}

function buildMeta(column: Record<string, any>) {
  const th: string[] = []
  const td: string[] = []
  const thStyle: Record<string, string> = {}
  const tdStyle: Record<string, string> = {}

  const width = toCssSize(column.width)
  if (width) {
    thStyle.width = width
    tdStyle.width = width
  }
  const minWidth = toCssSize(column.minWidth)
  if (minWidth) {
    thStyle.minWidth = minWidth
    tdStyle.minWidth = minWidth
  }
  if (column.align === 'right') td.push('text-right')
  if (column.align === 'center') td.push('text-center')
  if (column.headerAlign === 'right') th.push('text-right')
  else if (column.headerAlign === 'center') th.push('text-center')
  if (column.className) {
    th.push(column.className)
    td.push(column.className)
  }
  if (column.showOverflowTooltip) {
    td.push('whitespace-nowrap')
  }

  return {
    class: { th: th.join(' '), td: td.join(' ') },
    style: { th: thStyle, td: tdStyle },
  }
}

const built = computed(() => {
  const nodes = flatten((slots.default?.() ?? []) as unknown[]).filter(isColumnNode)
  const columns: TableColumn<any>[] = []
  let expandSlot: ((params: any) => unknown) | null = null
  let selectionIndex = -1

  nodes.forEach((node, nodeIndex) => {
    const column = (node.props ?? {}) as Record<string, any>
    const childSlots = (node.children ?? {}) as Record<string, (params: any) => unknown>
    const id = String(column.prop || column.label || `column_${nodeIndex}`)

    if (column.type === 'selection') {
      selectionIndex = columns.length
      columns.push({
        id: '__selection__',
        enableSorting: false,
        enableHiding: false,
        meta: { class: { td: 'w-10', th: 'w-10' } },
        header: ({ table }: any) => {
          const selectableRows = table
            .getRowModel()
            .rows.filter(
              (row: any) => !column.selectable || column.selectable(row.original, row.index),
            )
          const selectedCount = selectableRows.filter((row: any) => row.getIsSelected()).length
          const allSelected =
            selectableRows.length > 0 && selectedCount === selectableRows.length
          return h(UCheckbox, {
            'modelValue': selectedCount > 0 && !allSelected ? 'indeterminate' : allSelected,
            'onUpdate:modelValue': (value: unknown) => {
              selectableRows.forEach((row: any) => row.toggleSelected(!!value))
            },
            'aria-label': '全选',
          })
        },
        cell: ({ row }: any) => {
          const selectable =
            !column.selectable || column.selectable(row.original, row.index)
          return h(UCheckbox, {
            'modelValue': row.getIsSelected(),
            'disabled': !selectable,
            'onUpdate:modelValue': (value: unknown) => {
              if (selectable) row.toggleSelected(!!value)
            },
            'aria-label': '选择本行',
          })
        },
      })
      return
    }

    if (column.type === 'index') {
      columns.push({
        id,
        header: column.label || '#',
        enableSorting: false,
        meta: buildMeta(column),
        cell: ({ row }: any) => {
          if (typeof column.index === 'number') return row.index + column.index
          if (typeof column.index === 'function') return column.index(row.index)
          return row.index + 1
        },
      })
      return
    }

    if (column.type === 'expand') {
      expandSlot = childSlots.default ?? null
      columns.push({
        id: `__expand_${nodeIndex}__`,
        enableSorting: false,
        enableHiding: false,
        meta: { class: { td: 'w-10', th: 'w-10' } },
        header: () => '',
        cell: ({ row }: any) =>
          h(UButton, {
            color: 'neutral',
            variant: 'ghost',
            size: 'xs',
            square: true,
            icon: 'i-lucide-chevron-down',
            'aria-label': row.getIsExpanded() ? '收起详情' : '展开详情',
            'ui': {
              leadingIcon: [
                'transition-transform duration-200',
                row.getIsExpanded() ? 'rotate-180' : '',
              ],
            },
            'onClick': () => row.toggleExpanded(),
          }),
      })
      return
    }

    const sortable = column.sortable === true || column.sortable === 'custom'
    const header = childSlots.header
      ? (context: any) => childSlots.header({ column, ...context })
      : sortable
        ? (context: any) => {
            const sorted = context.column.getIsSorted()
            return h(UButton, {
              color: 'neutral',
              variant: 'ghost',
              size: 'sm',
              class: '-mx-2.5',
              label: column.label || '',
              trailingIcon:
                sorted === 'asc'
                  ? 'i-lucide-arrow-up-narrow-wide'
                  : sorted === 'desc'
                    ? 'i-lucide-arrow-down-wide-narrow'
                    : 'i-lucide-arrow-up-down',
              onClick: () => context.column.toggleSorting(sorted === 'asc'),
            })
          }
        : column.label || ''

    const tableColumn: TableColumn<any> = {
      id,
      accessorFn: column.prop ? (row: any) => getByPath(row, column.prop) : undefined,
      header,
      enableSorting: sortable,
      meta: buildMeta(column),
    }

    if (childSlots.default) {
      tableColumn.cell = ({ row }: any) =>
        childSlots.default({
          row: row.original,
          $index: row.index,
          column,
        })
    } else if (column.formatter) {
      tableColumn.cell = ({ row }: any) =>
        column.formatter(row.original, column, row.original?.[column.prop], row.index)
    }

    columns.push(tableColumn)
  })

  return { columns, expandSlot, hasSelection: selectionIndex >= 0 }
})

const columns = computed(() => built.value.columns)
const expandSlot = computed(() => built.value.expandSlot)

const RenderSlot = (renderProps: { fn?: ((params: any) => unknown) | null; params?: any }) =>
  renderProps.fn?.(renderProps.params) ?? null

const rowSelection = ref<Record<string, boolean>>({})
const expanded = ref<Record<string, boolean>>({})
const sorting = ref<{ id: string; desc: boolean }[]>([])
const tableRef = ref<{ tableApi?: unknown }>()

function getRowId(row: any, index: number): string {
  const key = props.rowKey || 'id'
  const value = row?.[key]
  return String(value ?? index)
}

const selectedRows = computed(() =>
  props.data.filter((row, index) => rowSelection.value[getRowId(row, index)]),
)

watch(
  rowSelection,
  () => {
    if (built.value.hasSelection) emit('selection-change', selectedRows.value)
  },
  { deep: true },
)

let suppressSortEmit = false

watch(
  sorting,
  (value) => {
    if (suppressSortEmit) return
    const first = value[0]
    emit('sort-change', {
      prop: first?.id ?? null,
      order: first ? (first.desc ? 'descending' : 'ascending') : null,
    })
  },
  { deep: true },
)

function clearSort() {
  // 程序化清空排序时不再触发 sort-change，避免与页面自身的查询/重置重复请求
  suppressSortEmit = true
  sorting.value = []
  void nextTick(() => {
    suppressSortEmit = false
  })
}

function clearSelection() {
  rowSelection.value = {}
}

function getSelectionRows() {
  return selectedRows.value
}

function toggleRowSelection(row: any, selected?: boolean) {
  const index = props.data.indexOf(row)
  const id = getRowId(row, index)
  rowSelection.value = { ...rowSelection.value, [id]: selected ?? !rowSelection.value[id] }
}

function onRowSelect(_event: Event, row: any) {
  emit('row-click', row.original)
}

const rootStyle = computed(() => {
  if (!props.maxHeight) return undefined
  return {
    maxHeight: typeof props.maxHeight === 'number' ? `${props.maxHeight}px` : props.maxHeight,
    overflowY: 'auto' as const,
  }
})

const tableUi = computed(() => {
  const ui: Record<string, string> = {}
  if (props.size === 'small') {
    ui.th = 'px-3! py-2! text-xs!'
    ui.td = 'px-3! py-1.5! text-xs!'
  }
  return ui
})

defineExpose({
  clearSort,
  clearSelection,
  getSelectionRows,
  toggleRowSelection,
  get tableApi() {
    return tableRef.value?.tableApi
  },
})
</script>

<template>
  <div
    class="app-table w-full"
    :class="[border ? 'overflow-hidden rounded-lg ring ring-default' : '', stripe ? 'app-table-striped' : '']"
  >
    <UTable
      ref="tableRef"
      v-model:row-selection="rowSelection"
      v-model:expanded="expanded"
      v-model:sorting="sorting"
      :data="data"
      :columns="columns"
      :loading="loading"
      :sticky="!!maxHeight"
      :empty="emptyText"
      :get-row-id="getRowId"
      :ui="tableUi"
      :style="rootStyle"
      @select="onRowSelect"
    >
      <template #empty>
        <slot name="empty">
          <div class="py-10 text-center text-sm text-muted">{{ emptyText }}</div>
        </slot>
      </template>

      <template #expanded="{ row }">
        <RenderSlot :fn="expandSlot" :params="{ row: row.original, $index: row.index }" />
      </template>

      <template v-if="$slots.append" #caption>
        <slot name="append" />
      </template>
    </UTable>
  </div>
</template>

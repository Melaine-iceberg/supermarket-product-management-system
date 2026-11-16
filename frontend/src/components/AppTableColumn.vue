<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
/**
 * 声明式表格列（由 AppTable 读取，不会单独渲染）。
 * API 与 Element Plus 的 el-table-column 保持兼容，便于存量页面平滑迁移到 UTable。
 */
withDefaults(
  defineProps<{
    prop?: string
    label?: string
    type?: 'default' | 'selection' | 'index' | 'expand'
    width?: string | number
    minWidth?: string | number
    fixed?: boolean | 'left' | 'right'
    align?: 'left' | 'center' | 'right'
    headerAlign?: 'left' | 'center' | 'right'
    sortable?: boolean | 'custom'
    formatter?: (row: unknown, column: unknown, cellValue: unknown, index: number) => string
    showOverflowTooltip?: boolean
    className?: string
    index?: number | ((index: number) => number)
    selectable?: (row: any, index: number) => boolean
  }>(),
  {
    prop: '',
    label: '',
    type: 'default',
    width: '',
    minWidth: '',
    fixed: false,
    align: 'left',
    headerAlign: undefined,
    sortable: false,
    formatter: undefined,
    showOverflowTooltip: false,
    className: '',
    index: undefined,
    selectable: undefined,
  },
)
</script>

<template>
  <span class="hidden" aria-hidden="true" />
</template>

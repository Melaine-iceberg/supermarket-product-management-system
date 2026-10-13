<script setup lang="ts" generic="T extends object">
import { ref, watch } from 'vue'
import type { PageResult } from '@/api/request'
import type { DrillColumn } from './drill'

/**
 * 通用下钻单据列表抽屉（BR-RPT-06）：汇总数字 → 构成它的明细单据列表。
 * 列由调用方配置，可选 actions 插槽提供打开单据详情等操作。
 */
const props = defineProps<{
  modelValue: boolean
  title: string
  size?: string
  columns: DrillColumn<T>[]
  fetchPage: (page: number, size: number) => Promise<PageResult<T>>
}>()

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const SIZE = 20
const loading = ref(false)
const rows = ref<T[]>([])
const total = ref(0)
const page = ref(1)

async function load(p: number) {
  loading.value = true
  try {
    const data = await props.fetchPage(p, SIZE)
    rows.value = data.list
    total.value = data.total
    page.value = p
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) load(1)
  },
)

function close() {
  emit('update:modelValue', false)
}

function display(v: unknown) {
  return v == null || v === '' ? '—' : String(v)
}
</script>

<template>
  <el-drawer :model-value="modelValue" :title="title" :size="size ?? '70%'" @update:model-value="close">
    <el-table v-loading="loading" border :data="rows" stripe size="small">
      <el-table-column
        v-for="(col, i) in columns"
        :key="i"
        :label="col.label"
        :width="col.width"
        :min-width="col.minWidth"
        :align="col.align ?? 'left'"
      >
        <template #default="{ row }">{{ display(col.value(row as T)) }}</template>
      </el-table-column>
      <el-table-column v-if="$slots.actions" label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <slot name="actions" :row="row as T" />
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      :total="total"
      :page-size="SIZE"
      layout="total, prev, pager, next"
      class="pager"
      @current-change="load"
    />
  </el-drawer>
</template>

<style scoped lang="scss">
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>

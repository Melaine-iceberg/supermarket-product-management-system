<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { PageResult } from '@/api/request'
import type { DrillOrderRow } from '@/api/report'

/**
 * 汇总数字下钻抽屉（BR-RPT-06）：构成汇总的销售单列表 → 逐单可打开明细（AC-16）。
 * fetchPage 由调用方闭包当日过滤条件，保证与报表行同口径。
 */
const props = defineProps<{
  modelValue: boolean
  title: string
  fetchPage: (page: number, size: number) => Promise<PageResult<DrillOrderRow>>
}>()

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const SIZE = 20
const router = useRouter()
const loading = ref(false)
const rows = ref<DrillOrderRow[]>([])
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

/** 打开单据详情：销售单页支持 ?orderNo= 深链并自动弹出详情 */
function openOrder(row: DrillOrderRow) {
  const { href } = router.resolve({ path: '/sale/order', query: { orderNo: row.orderNo } })
  window.open(href, '_blank')
}

function money(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2)
}

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<template>
  <el-drawer :model-value="modelValue" :title="title" size="70%" @update:model-value="close">
    <el-table v-loading="loading" :data="rows" stripe size="small">
      <el-table-column prop="orderNo" label="销售单号" width="170" />
      <el-table-column prop="cashierName" label="收银员" width="90" />
      <el-table-column label="台号" width="60" align="center">
        <template #default="{ row }">{{ String(row.counterNo).padStart(2, '0') }}</template>
      </el-table-column>
      <el-table-column label="会员" width="100">
        <template #default="{ row }">{{ row.memberName ?? '散客' }}</template>
      </el-table-column>
      <el-table-column label="销售时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.saleTime) }}</template>
      </el-table-column>
      <el-table-column label="件数" prop="itemCount" width="60" align="right" />
      <el-table-column label="应收" width="90" align="right">
        <template #default="{ row }">{{ money(row.payableAmount) }}</template>
      </el-table-column>
      <el-table-column label="实收" width="90" align="right">
        <template #default="{ row }">{{ money(row.paidAmount) }}</template>
      </el-table-column>
      <el-table-column label="毛利额" width="90" align="right">
        <template #default="{ row }">{{ money(row.profitAmount) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="70" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openOrder(row as DrillOrderRow)">查看</el-button>
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

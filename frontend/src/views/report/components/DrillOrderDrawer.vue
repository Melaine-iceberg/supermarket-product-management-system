<script setup lang="ts">
import { moneyText } from '@/utils/format'
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

/** 仅在关闭时回写，避免开关事件双向触发 */
function onVisibleChange(value: boolean) {
  if (!value) close()
}

/** 打开单据详情：销售单页支持 ?orderNo= 深链并自动弹出详情 */
function openOrder(row: DrillOrderRow) {
  const { href } = router.resolve({ path: '/sale/order', query: { orderNo: row.orderNo } })
  window.open(href, '_blank')
}

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
}

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<template>
  <AppDrawer :model-value="modelValue" :title="title" size="70%" @update:model-value="onVisibleChange">
    <AppTable :loading="loading" border :data="rows" stripe size="small">
      <AppTableColumn prop="orderNo" label="销售单号" width="170" />
      <AppTableColumn prop="cashierName" label="收银员" width="90" />
      <AppTableColumn label="台号" width="60" align="center">
        <template #default="{ row }">{{ String(row.counterNo).padStart(2, '0') }}</template>
      </AppTableColumn>
      <AppTableColumn label="会员" width="100">
        <template #default="{ row }">{{ row.memberName ?? '散客' }}</template>
      </AppTableColumn>
      <AppTableColumn label="销售时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.saleTime) }}</template>
      </AppTableColumn>
      <AppTableColumn label="件数" prop="itemCount" width="60" align="right" />
      <AppTableColumn label="应收" width="90" align="right">
        <template #default="{ row }">{{ money(row.payableAmount) }}</template>
      </AppTableColumn>
      <AppTableColumn label="实收" width="90" align="right">
        <template #default="{ row }">{{ money(row.paidAmount) }}</template>
      </AppTableColumn>
      <AppTableColumn label="毛利额" width="90" align="right">
        <template #default="{ row }">{{ money(row.profitAmount) }}</template>
      </AppTableColumn>
      <AppTableColumn label="操作" width="70" fixed="right">
        <template #default="{ row }">
          <UButton variant="link" @click="openOrder(row as DrillOrderRow)">查看</UButton>
        </template>
      </AppTableColumn>
    </AppTable>
    <AppPagination
      v-model:current-page="page"
      :total="total"
      :page-size="SIZE"
      layout="total, prev, pager, next"
      class="pager"
      @current-change="load"
    />
  </AppDrawer>
</template>

<style scoped lang="scss">
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>

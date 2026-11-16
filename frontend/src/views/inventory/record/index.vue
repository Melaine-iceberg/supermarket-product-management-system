<script setup lang="ts">
import { moneyText } from '@/utils/format'
import { onMounted, reactive, ref } from 'vue'
import { pageInventoryRecords, type InventoryRecordVO } from '@/api/inventory'

/** 流水类型（§8.2）：入库为正、出库为负，流水只增不改不删（BR-INV-04） */
const recordTypeMap: Record<string, { label: string; type: 'success' | 'warning' | 'danger' | 'info' | 'primary' }> = {
  INIT: { label: '期初建账', type: 'info' },
  PURCHASE_IN: { label: '采购入库', type: 'success' },
  PURCHASE_RETURN: { label: '采购退货', type: 'warning' },
  SALE_OUT: { label: '销售出库', type: 'primary' },
  SALE_RETURN: { label: '销售退货', type: 'warning' },
  CHECK_SURPLUS: { label: '盘盈调整', type: 'success' },
  CHECK_SHORTAGE: { label: '盘亏调整', type: 'danger' },
  LOSS_OUT: { label: '报损出库', type: 'danger' },
  OVERFLOW_IN: { label: '报溢入库', type: 'success' },
}

/** Element 标签类型 → Nuxt UI 徽标颜色 */
function tagColor(type?: 'success' | 'warning' | 'danger' | 'info' | 'primary') {
  if (type === 'success') return 'success' as const
  if (type === 'warning') return 'warning' as const
  if (type === 'danger') return 'error' as const
  if (type === 'primary') return 'primary' as const
  return 'neutral' as const
}

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 4)
}

function qty(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toString()
}

/** 变动数量：正入库（绿）、负出库（红） */
function qtyClass(v: number) {
  return v > 0 ? 'in' : v < 0 ? 'out' : ''
}

// ---------- 查询（FR-INV-03：商品/类型/单据号/操作人/时间区间，时间倒序） ----------
const loading = ref(false)
const list = ref<InventoryRecordVO[]>([])
const total = ref(0)
const query = reactive({
  goodsName: '',
  recordType: '' as string,
  bizNo: '',
  operatorName: '',
  dateRange: null as string[] | null,
  page: 1,
  size: 20,
})

async function load() {
  loading.value = true
  try {
    const data = await pageInventoryRecords({
      goodsName: query.goodsName.trim() || undefined,
      recordType: query.recordType || undefined,
      bizNo: query.bizNo.trim() || undefined,
      operatorName: query.operatorName.trim() || undefined,
      beginDate: query.dateRange?.[0],
      endDate: query.dateRange?.[1],
      page: query.page,
      size: query.size,
    })
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

function resetQuery() {
  Object.assign(query, { goodsName: '', recordType: '', bizNo: '', operatorName: '', dateRange: null, page: 1 })
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <UCard class="search-card" :ui="{ body: 'pb-0.5' }">
      <AppForm inline @submit.prevent>
        <AppFormItem label="商品名称">
          <AppInput v-model="query.goodsName" placeholder="名称模糊查询" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="变动类型">
          <AppSelect v-model="query.recordType" clearable placeholder="全部" style="width: 130px">
            <AppOption
              v-for="(t, k) in recordTypeMap"
              :key="k"
              :label="t.label"
              :value="k"
            />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="关联单号">
          <AppInput v-model="query.bizNo" placeholder="采购/销售/盘点单号" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="操作人">
          <AppInput v-model="query.operatorName" placeholder="用户名" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="变动日期">
          <AppDatePicker
            v-model="query.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </AppFormItem>
        <AppFormItem>
          <UButton label="查询" @click="search" />
          <UButton color="neutral" variant="outline" label="重置" @click="resetQuery" />
        </AppFormItem>
      </AppForm>
    </UCard>

    <UCard>
      <template #header>
        <div class="card-head">
          <span>库存流水</span>
          <span class="head-tip">流水只增不改不删（BR-INV-04），任何纠错通过新增反向记录实现</span>
        </div>
      </template>

      <AppTable :loading="loading" border :data="list" stripe>
        <AppTableColumn prop="operateTime" label="变动时间" width="165">
          <template #default="{ row }">{{ fmtTime(row.operateTime) }}</template>
        </AppTableColumn>
        <AppTableColumn label="商品" min-width="180">
          <template #default="{ row }">
            <div class="goods-name">{{ row.goodsName }}</div>
            <div class="sub">{{ row.goodsCode }} / {{ row.unitName }}</div>
          </template>
        </AppTableColumn>
        <AppTableColumn label="变动类型" width="100" align="center">
          <template #default="{ row }">
            <UBadge :color="tagColor(recordTypeMap[row.recordType]?.type)" variant="soft" size="sm">
              {{ recordTypeMap[row.recordType]?.label ?? row.recordType }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="变动数量" width="100" align="right">
          <template #default="{ row }">
            <span class="qty" :class="qtyClass(row.changeQty)">{{ qty(row.changeQty) }}</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="变动前" width="90" align="right">
          <template #default="{ row }">{{ qty(row.beforeQty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="变动后" width="90" align="right">
          <template #default="{ row }">{{ qty(row.afterQty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="成本单价" width="90" align="right">
          <template #default="{ row }">{{ money(row.unitCost) }}</template>
        </AppTableColumn>
        <AppTableColumn label="关联单据" width="160">
          <template #default="{ row }">
            <span v-if="row.bizNo" class="biz-no">{{ row.bizNo }}</span>
            <span v-else>—</span>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="operatorName" label="操作人" width="90" />
        <AppTableColumn label="备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </AppTableColumn>
      </AppTable>

      <AppPagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @change="load"
      />
    </UCard>
  </div>
</template>

<style scoped lang="scss">
.page {
  .search-card {
    margin-bottom: 12px;
  }

  .card-head {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .head-tip {
      font-size: 12px;
      color: var(--el-text-color-secondary);
      font-weight: 400;
    }
  }

  .goods-name {
    font-weight: 500;
  }

  .sub {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .qty {
    font-variant-numeric: tabular-nums;
    font-weight: 600;

    &.in {
      color: var(--el-color-success);
    }

    &.out {
      color: var(--el-color-danger);
    }
  }

  .biz-no {
    font-family: monospace;
    font-size: 13px;
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }
}
</style>

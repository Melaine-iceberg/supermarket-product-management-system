<script setup lang="ts">
import { notify } from '@/utils/notify'
import { moneyText } from '@/utils/format'
import { onMounted, reactive, ref } from 'vue'
import { getCategoryTree, type CategoryNode } from '@/api/goods'
import { useAuthStore } from '@/stores/auth'
import {
  dropEmptyColumns,
  pMoney,
  pPercent,
  pQty,
  printSheet,
  type PrintColumn,
} from '@/utils/print'
import {
  exportProfitReport,
  fetchDrillOrders,
  fetchNegativeProfit,
  fetchProfitByCategory,
  fetchProfitByDay,
  fetchProfitByGoods,
  todayRange,
  type DrillOrderFilters,
  type GoodsRankRow,
  type NegativeProfitRow,
  type ReportRange,
  type SalesReportRow,
  type SalesReportVO,
} from '@/api/report'
import DrillOrderDrawer from '../components/DrillOrderDrawer.vue'
import { RANGE_PRESETS, presetRange, type RangePreset } from '../components/rangePresets'

type ViewKey = 'by-goods' | 'by-category' | 'by-day' | 'negative'

const VIEW_LABELS: Record<ViewKey, string> = {
  'by-goods': '按商品',
  'by-category': '按分类',
  'by-day': '按时段',
  negative: '负毛利清单',
}

const loading = ref(false)
const exporting = ref(false)
const auth = useAuthStore()
const view = ref<ViewKey>('by-goods')
const initRange = todayRange()
const range = reactive<ReportRange>({ ...initRange })
/** 日期选择器模型（el-date-picker 需要数组），查询前同步进 range */
const dateRange = ref<string[]>([initRange.beginDate, initRange.endDate])
const categoryId = ref<number>()

const categoryTree = ref<CategoryNode[]>([])

// 数据容器
const goodsRows = ref<GoodsRankRow[]>([])
const goodsTotal = ref(0)
const categoryRows = ref<GoodsRankRow[]>([])
const dayData = ref<SalesReportVO | null>(null)
const negativeRows = ref<NegativeProfitRow[]>([])

const page = ref(1)
const size = ref(20)

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
}

function percent(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2) + '%'
}

function qty(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2)
}

function syncRange() {
  range.beginDate = dateRange.value?.[0]
  range.endDate = dateRange.value?.[1]
}

function setRange(preset: RangePreset) {
  const r = presetRange(preset)
  dateRange.value = [r.beginDate, r.endDate]
  load()
}

async function load() {
  syncRange()
  loading.value = true
  try {
    if (view.value === 'by-goods') {
      const data = await fetchProfitByGoods(range, categoryId.value, page.value, size.value)
      goodsRows.value = data.list
      goodsTotal.value = data.total
    } else if (view.value === 'by-category') {
      categoryRows.value = await fetchProfitByCategory(range)
    } else if (view.value === 'by-day') {
      dayData.value = await fetchProfitByDay(range)
    } else {
      negativeRows.value = await fetchNegativeProfit(range)
    }
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function handlePageChange(p: number) {
  page.value = p
  load()
}

async function handleExport() {
  syncRange()
  exporting.value = true
  try {
    await exportProfitReport(view.value, range, { categoryId: categoryId.value })
    notify.success('导出成功')
  } finally {
    exporting.value = false
  }
}

// ---------- 打印视图（FR-RPT-09）：列与后端 Excel 导出口径一致 ----------
const PROFIT_GOODS_COLUMNS: PrintColumn<GoodsRankRow>[] = [
  { label: '商品编码', value: (r) => r.goodsCode },
  { label: '商品名称', value: (r) => r.goodsName },
  { label: '分类', value: (r) => r.categoryName },
  { label: '品牌', value: (r) => r.brandName },
  { label: '单数', value: (r) => r.orderCount, align: 'right' },
  { label: '销量', value: (r) => pQty(r.qty), align: 'right' },
  { label: '销售额', value: (r) => pMoney(r.salesAmount), align: 'right' },
  { label: '优惠额', value: (r) => pMoney(r.discountAmount), align: 'right' },
  { label: '净销售额', value: (r) => pMoney(r.netSalesAmount), align: 'right' },
  { label: '退货额', value: (r) => pMoney(r.refundAmount), align: 'right' },
  { label: '销售成本', value: (r) => pMoney(r.costAmount), align: 'right' },
  { label: '毛利额', value: (r) => pMoney(r.profitAmount), align: 'right' },
  { label: '毛利率', value: (r) => pPercent(r.profitRate), align: 'right' },
  { label: '占比', value: (r) => pPercent(r.shareRate), align: 'right' },
]

const PROFIT_DAY_COLUMNS: PrintColumn<SalesReportRow>[] = [
  { label: '日期', value: (r) => r.groupKey },
  { label: '单数', value: (r) => r.orderCount, align: 'right' },
  { label: '件数', value: (r) => r.itemCount, align: 'right' },
  { label: '销售额', value: (r) => pMoney(r.salesAmount), align: 'right' },
  { label: '优惠额', value: (r) => pMoney(r.discountAmount), align: 'right' },
  { label: '净销售额', value: (r) => pMoney(r.netSalesAmount), align: 'right' },
  { label: '退货额', value: (r) => pMoney(r.refundAmount), align: 'right' },
  { label: '净销售', value: (r) => pMoney(r.netAmount), align: 'right' },
  { label: '销售成本', value: (r) => pMoney(r.costAmount), align: 'right' },
  { label: '毛利额', value: (r) => pMoney(r.profitAmount), align: 'right' },
  { label: '毛利率', value: (r) => pPercent(r.profitRate), align: 'right' },
  { label: '客单价', value: (r) => pMoney(r.avgOrderAmount), align: 'right' },
]

const NEGATIVE_COLUMNS: PrintColumn<NegativeProfitRow>[] = [
  { label: '商品编码', value: (r) => r.goodsCode },
  { label: '商品名称', value: (r) => r.goodsName },
  { label: '分类', value: (r) => r.categoryName },
  { label: '成交单价', value: (r) => pMoney(r.unitPrice), align: 'right' },
  { label: '成本单价', value: (r) => pMoney(r.costPrice), align: 'right' },
  { label: '单位毛利', value: (r) => pMoney(r.unitProfit), align: 'right' },
  { label: '销量', value: (r) => pQty(r.qty), align: 'right' },
  { label: '单数', value: (r) => r.orderCount, align: 'right' },
  { label: '实付金额', value: (r) => pMoney(r.paidAmount), align: 'right' },
  { label: '销售成本', value: (r) => pMoney(r.costAmount), align: 'right' },
  { label: '毛利额', value: (r) => pMoney(r.profitAmount), align: 'right' },
]

async function handlePrint() {
  syncRange()
  const title = `毛利分析 · ${VIEW_LABELS[view.value]}`
  const meta = [
    `统计区间：${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}`,
    `操作员：${auth.user?.realName ?? '—'}`,
  ]
  if (view.value === 'by-goods') {
    await printSheet({
      title,
      meta: [...meta, `第 ${page.value} 页 / 共 ${goodsTotal.value} 条`],
      columns: dropEmptyColumns(PROFIT_GOODS_COLUMNS, goodsRows.value),
      rows: goodsRows.value,
      orientation: 'landscape',
    })
  } else if (view.value === 'by-category') {
    await printSheet({
      title,
      meta,
      columns: dropEmptyColumns(PROFIT_GOODS_COLUMNS, categoryRows.value),
      rows: categoryRows.value,
      orientation: 'landscape',
    })
  } else if (view.value === 'by-day') {
    const t = dayData.value?.total
    await printSheet({
      title,
      meta,
      columns: PROFIT_DAY_COLUMNS,
      rows: dayData.value?.rows ?? [],
      totals: t ? [`合计毛利额：￥${pMoney(t.profitAmount)}`, `综合毛利率：${pPercent(t.profitRate)}`] : [],
      orientation: 'landscape',
    })
  } else {
    await printSheet({
      title: `${title}（成交价低于成本价）`,
      meta,
      columns: NEGATIVE_COLUMNS,
      rows: negativeRows.value,
      orientation: 'landscape',
    })
  }
}

// ---------- 下钻（BR-RPT-06）：汇总数字 → 构成它的销售单列表（毛利字段按角色过滤） ----------
const drillVisible = ref(false)
const drillTitle = ref('')
let drillFilters: DrillOrderFilters = {}

function openDrill(filters: DrillOrderFilters, label: string) {
  drillFilters = filters
  drillTitle.value = `下钻：${label}`
  drillVisible.value = true
}

function fetchDrillPage(p: number, size: number) {
  return fetchDrillOrders('profit', { ...drillFilters, page: p, size })
}

function drillGoodsRow(row: { goodsId: number | null; goodsName: string | null }) {
  syncRange()
  if (!row.goodsId) return
  openDrill({ goodsId: row.goodsId, ...range }, `商品 ${row.goodsName ?? ''}`)
}

function drillCategoryRow(row: GoodsRankRow) {
  syncRange()
  if (!row.categoryId) return
  openDrill({ categoryId: row.categoryId, ...range }, `分类 ${row.categoryName ?? ''}`)
}

function drillDayRow(row: SalesReportRow) {
  syncRange()
  const day = (row.bizDate ?? row.groupKey).slice(0, 10)
  openDrill({ beginDate: day, endDate: day }, `营业日 ${day}`)
}

function drillTotalRow() {
  syncRange()
  openDrill({ ...range }, `合计（${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}）`)
}

onMounted(async () => {
  categoryTree.value = await getCategoryTree()
  load()
})
</script>

<template>
  <div class="page">
    <!-- 筛选区 -->
    <UCard class="search-card">
      <AppForm inline @submit.prevent>
        <AppFormItem label="维度" label-as="span">
          <AppRadioGroup v-model="view" @change="search">
            <AppRadioButton v-for="(label, key) in VIEW_LABELS" :key="key" :value="key">
              {{ label }}
            </AppRadioButton>
          </AppRadioGroup>
        </AppFormItem>
        <AppFormItem v-if="view === 'by-goods'" label="分类">
          <AppTreeSelect
            v-model="categoryId"
            :data="categoryTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            clearable
            placeholder="全部分类"
            style="width: 180px"
          />
        </AppFormItem>
        <AppFormItem label="日期区间">
          <AppDatePicker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </AppFormItem>
        <AppFormItem>
          <div class="flex items-center gap-2">
            <UButton @click="search">查询</UButton>
            <UButton :loading="exporting" color="neutral" variant="outline" @click="handleExport">导出</UButton>
            <UButton color="neutral" variant="outline" @click="handlePrint">打印</UButton>
          </div>
        </AppFormItem>
      </AppForm>
      <div class="presets">
        <UButton
          v-for="p in RANGE_PRESETS"
          :key="p.key"
          size="sm"
          color="neutral"
          variant="outline"
          @click="setRange(p.key)"
        >
          {{ p.label }}
        </UButton>
      </div>
    </UCard>

    <!-- 按商品毛利 -->
    <UCard v-if="view === 'by-goods'">
      <template #header><span>商品毛利分析</span></template>
      <AppTable :loading="loading" border :data="goodsRows" stripe>
        <AppTableColumn type="index" label="#" width="50" align="center" />
        <AppTableColumn prop="goodsCode" label="商品编码" width="120" />
        <AppTableColumn prop="goodsName" label="商品名称" min-width="160" show-overflow-tooltip />
        <AppTableColumn prop="categoryName" label="分类" width="120" show-overflow-tooltip />
        <AppTableColumn prop="brandName" label="品牌" width="100" show-overflow-tooltip />
        <AppTableColumn label="单数" prop="orderCount" width="70" align="right" />
        <AppTableColumn label="销量" width="80" align="right">
          <template #default="{ row }">{{ qty(row.qty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="净销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.netSalesAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="销售成本" width="100" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="毛利额" width="100" align="right">
          <template #default="{ row }">
            <span :class="{ profit: Number(row.profitAmount ?? 0) >= 0, loss: Number(row.profitAmount ?? 0) < 0 }">
              {{ money(row.profitAmount) }}
            </span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="毛利率" width="80" align="right">
          <template #default="{ row }">{{ percent(row.profitRate) }}</template>
        </AppTableColumn>
        <AppTableColumn label="占比" width="80" align="right">
          <template #default="{ row }">{{ percent(row.shareRate) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton variant="link" @click="drillGoodsRow(row as GoodsRankRow)">下钻</UButton>
          </template>
        </AppTableColumn>
      </AppTable>
      <AppPagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="goodsTotal"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="handlePageChange"
      />
    </UCard>

    <!-- 按分类毛利 -->
    <UCard v-if="view === 'by-category'">
      <template #header><span>分类毛利分析</span></template>
      <AppTable :loading="loading" border :data="categoryRows" stripe>
        <AppTableColumn type="index" label="#" width="50" align="center" />
        <AppTableColumn prop="categoryName" label="分类" min-width="160" />
        <AppTableColumn label="单数" prop="orderCount" width="80" align="right" />
        <AppTableColumn label="销量" width="90" align="right">
          <template #default="{ row }">{{ qty(row.qty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="销售额" width="110" align="right">
          <template #default="{ row }">{{ money(row.salesAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="净销售额" width="110" align="right">
          <template #default="{ row }">{{ money(row.netSalesAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="销售成本" width="110" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="毛利额" width="110" align="right">
          <template #default="{ row }">
            <span :class="{ profit: Number(row.profitAmount ?? 0) >= 0, loss: Number(row.profitAmount ?? 0) < 0 }">
              {{ money(row.profitAmount) }}
            </span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="毛利率" width="90" align="right">
          <template #default="{ row }">{{ percent(row.profitRate) }}</template>
        </AppTableColumn>
        <AppTableColumn label="占比" width="80" align="right">
          <template #default="{ row }">{{ percent(row.shareRate) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton variant="link" @click="drillCategoryRow(row as GoodsRankRow)">下钻</UButton>
          </template>
        </AppTableColumn>
      </AppTable>
    </UCard>

    <!-- 按时段毛利 -->
    <UCard v-if="view === 'by-day'">
      <template #header><span>时段毛利分析（按营业日）</span></template>
      <AppTable :loading="loading" border :data="dayData?.rows ?? []" stripe>
        <AppTableColumn prop="bizDate" label="日期" width="110" />
        <AppTableColumn label="单数" prop="orderCount" width="70" align="right" />
        <AppTableColumn label="件数" width="70" align="right">
          <template #default="{ row }">{{ row.itemCount ?? '—' }}</template>
        </AppTableColumn>
        <AppTableColumn label="销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.salesAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="净销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.netSalesAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="退货额" width="90" align="right">
          <template #default="{ row }">{{ money(row.refundAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="净销售" width="100" align="right">
          <template #default="{ row }">
            <b>{{ money(row.netAmount) }}</b>
          </template>
        </AppTableColumn>
        <AppTableColumn label="销售成本" width="100" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="毛利额" width="100" align="right">
          <template #default="{ row }">
            <span :class="{ profit: Number(row.profitAmount ?? 0) >= 0, loss: Number(row.profitAmount ?? 0) < 0 }">
              {{ money(row.profitAmount) }}
            </span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="毛利率" width="80" align="right">
          <template #default="{ row }">{{ percent(row.profitRate) }}</template>
        </AppTableColumn>
        <AppTableColumn label="客单价" width="90" align="right">
          <template #default="{ row }">{{ money(row.avgOrderAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton variant="link" @click="drillDayRow(row as SalesReportRow)">下钻</UButton>
          </template>
        </AppTableColumn>
      </AppTable>
      <div v-if="dayData?.total" class="total-row">
        <span class="total-label">合计</span>
        <span>单数: {{ dayData.total.orderCount }}</span>
        <span>净销售: ￥{{ money(dayData.total.netAmount) }}</span>
        <span>毛利额: ￥{{ money(dayData.total.profitAmount) }}</span>
        <span>毛利率: {{ percent(dayData.total.profitRate) }}</span>
        <UButton variant="link" @click="drillTotalRow">下钻</UButton>
      </div>
    </UCard>

    <!-- 负毛利清单 -->
    <UCard v-if="view === 'negative'">
      <template #header>
        <span>负毛利商品清单</span>
        <UBadge color="error" variant="soft" size="sm" class="header-tag">成交价低于成本价</UBadge>
      </template>
      <AppTable :loading="loading" border :data="negativeRows" stripe>
        <AppTableColumn type="index" label="#" width="50" align="center" />
        <AppTableColumn prop="goodsCode" label="商品编码" width="120" />
        <AppTableColumn prop="goodsName" label="商品名称" min-width="160" show-overflow-tooltip />
        <AppTableColumn prop="categoryName" label="分类" width="120" show-overflow-tooltip />
        <AppTableColumn label="成交单价" width="90" align="right">
          <template #default="{ row }">{{ money(row.unitPrice) }}</template>
        </AppTableColumn>
        <AppTableColumn label="成本单价" width="90" align="right">
          <template #default="{ row }">{{ money(row.costPrice) }}</template>
        </AppTableColumn>
        <AppTableColumn label="单位毛利" width="90" align="right">
          <template #default="{ row }">
            <span class="loss">{{ money(row.unitProfit) }}</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="销量" width="80" align="right">
          <template #default="{ row }">{{ qty(row.qty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="单数" prop="orderCount" width="70" align="right" />
        <AppTableColumn label="实付金额" width="100" align="right">
          <template #default="{ row }">{{ money(row.paidAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="销售成本" width="100" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="毛利额" width="100" align="right">
          <template #default="{ row }">
            <span class="loss">{{ money(row.profitAmount) }}</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton variant="link" @click="drillGoodsRow(row as NegativeProfitRow)">下钻</UButton>
          </template>
        </AppTableColumn>
      </AppTable>
    </UCard>

    <!-- 下钻单据列表（BR-RPT-06） -->
    <DrillOrderDrawer v-model="drillVisible" :title="drillTitle" :fetch-page="fetchDrillPage" />
  </div>
</template>

<style scoped lang="scss">
.page {
  .search-card {
    margin-bottom: 16px;
  }

  .presets {
    margin-top: 8px;
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }

  .pager {
    margin-top: 16px;
    justify-content: flex-end;
  }

  .header-tag {
    margin-left: 8px;
  }

  .total-row {
    display: flex;
    gap: 24px;
    padding: 12px 16px;
    background: var(--el-bg-color-page);
    border-top: 1px solid var(--el-border-color-lighter);
    font-size: 13px;
    font-weight: 600;

    .total-label {
      color: var(--el-color-primary);
    }
  }

  .profit {
    color: var(--el-color-success);
  }

  .loss {
    color: var(--el-color-danger);
  }
}
</style>
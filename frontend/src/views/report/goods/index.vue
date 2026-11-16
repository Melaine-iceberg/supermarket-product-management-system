<script setup lang="ts">
import { notify } from '@/utils/notify'
import { moneyText } from '@/utils/format'
import { cssColor } from '@/utils/css'
import { onMounted, reactive, ref, shallowRef } from 'vue'
import * as echarts from 'echarts'
import { getCategoryTree, type CategoryNode } from '@/api/goods'
import { useAuthStore } from '@/stores/auth'
import { dropEmptyColumns, pMoney, pPercent, pQty, printSheet, type PrintColumn } from '@/utils/print'
import {
  exportGoodsReport,
  fetchBrandSales,
  fetchCategoryStructure,
  fetchDrillOrders,
  fetchGoodsRanking,
  fetchSlowMoving,
  todayRange,
  type GoodsRankRow,
  type ReportRange,
} from '@/api/report'
import DrillOrderDrawer from '../components/DrillOrderDrawer.vue'
import { RANGE_PRESETS, presetRange, type RangePreset } from '../components/rangePresets'

type ViewKey = 'ranking' | 'category-structure' | 'slow-moving' | 'brand-sales'

const VIEW_LABELS: Record<ViewKey, string> = {
  ranking: '商品销售排行',
  'category-structure': '品类销售结构',
  'slow-moving': '滞销商品清单',
  'brand-sales': '品牌销售分析',
}

const loading = ref(false)
const exporting = ref(false)
const auth = useAuthStore()
const view = ref<ViewKey>('ranking')
const initRange = todayRange()
const range = reactive<ReportRange>({ ...initRange })
/** 日期选择器模型（el-date-picker 需要数组），查询前同步进 range */
const dateRange = ref<string[]>([initRange.beginDate, initRange.endDate])
const orderBy = ref<'qty' | 'salesAmount' | 'profitAmount' | 'netSalesAmount'>('salesAmount')
const categoryId = ref<number>()
const threshold = ref(1)

const rows = ref<GoodsRankRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const categoryTree = ref<CategoryNode[]>([])
const pieChartRef = ref<HTMLDivElement>()
const pieChart = shallowRef<echarts.ECharts>()

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
    if (view.value === 'ranking') {
      const data = await fetchGoodsRanking(range, orderBy.value, categoryId.value, page.value, size.value)
      rows.value = data.list
      total.value = data.total
    } else if (view.value === 'category-structure') {
      rows.value = await fetchCategoryStructure(range)
      total.value = rows.value.length
      renderPie()
    } else if (view.value === 'slow-moving') {
      const data = await fetchSlowMoving(range, threshold.value, categoryId.value, page.value, size.value)
      rows.value = data.list
      total.value = data.total
    } else {
      rows.value = await fetchBrandSales(range)
      total.value = rows.value.length
    }
  } finally {
    loading.value = false
  }
}

function renderPie() {
  if (!pieChartRef.value || view.value !== 'category-structure') return
  if (!pieChart.value) {
    pieChart.value = echarts.init(pieChartRef.value)
  }
  pieChart.value.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: ￥{c} ({d}%)' },
    legend: { type: 'scroll', orient: 'vertical', right: 10, top: 20, bottom: 20 },
    series: [
      {
        name: '品类销售额',
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 6,
          borderColor: cssColor('--el-bg-color', '#fff'),
          borderWidth: 2,
        },
        label: { show: false },
        emphasis: { label: { show: true, fontSize: 14, fontWeight: 'bold' } },
        data: rows.value.map((r) => ({
          name: r.categoryName ?? '未分类',
          value: Number(r.salesAmount ?? 0),
        })),
      },
    ],
  })
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
    await exportGoodsReport(view.value, range, {
      orderBy: orderBy.value,
      threshold: threshold.value,
      categoryId: categoryId.value,
    })
    notify.success('导出成功')
  } finally {
    exporting.value = false
  }
}

// ---------- 打印视图（FR-RPT-09，A4 横向） ----------
async function handlePrint() {
  syncRange()
  const columns: PrintColumn<GoodsRankRow>[] = []
  if (view.value === 'category-structure') {
    columns.push({ label: '分类', value: (r) => r.categoryName })
  } else if (view.value === 'brand-sales') {
    columns.push({ label: '品牌', value: (r) => r.brandName })
  } else {
    columns.push(
      { label: '商品编码', value: (r) => r.goodsCode },
      { label: '商品名称', value: (r) => r.goodsName },
      { label: '分类', value: (r) => r.categoryName },
    )
    if (view.value === 'ranking') {
      columns.push({ label: '品牌', value: (r) => r.brandName })
    }
  }
  columns.push(
    { label: '单数', value: (r) => r.orderCount, align: 'right' },
    { label: '销量', value: (r) => pQty(r.qty), align: 'right' },
    { label: '销售额', value: (r) => pMoney(r.salesAmount), align: 'right' },
    { label: '净销售额', value: (r) => pMoney(r.netSalesAmount), align: 'right' },
    { label: '毛利额', value: (r) => pMoney(r.profitAmount), align: 'right' },
    { label: '毛利率', value: (r) => pPercent(r.profitRate), align: 'right' },
    { label: '占比', value: (r) => pPercent(r.shareRate), align: 'right' },
  )
  if (view.value === 'slow-moving') {
    columns.push(
      { label: '当前库存', value: (r) => pQty(r.stockQty), align: 'right' },
      { label: '占用资金', value: (r) => pMoney(r.occupiedAmount), align: 'right' },
    )
  }
  const paged = view.value === 'ranking' || view.value === 'slow-moving'
  await printSheet({
    title: `商品销售分析 · ${VIEW_LABELS[view.value]}`,
    meta: [
      `统计区间：${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}`,
      view.value === 'slow-moving' ? `滞销阈值：区间销量低于 ${threshold.value}` : null,
      paged ? `第 ${page.value} 页 / 共 ${total.value} 条` : `共 ${rows.value.length} 条`,
      `操作员：${auth.user?.realName ?? '—'}`,
    ],
    columns: dropEmptyColumns(columns, rows.value),
    rows: rows.value,
    orientation: 'landscape',
  })
}

// ---------- 下钻（BR-RPT-06）：汇总行 → 构成它的销售单列表 ----------
const drillVisible = ref(false)
const drillTitle = ref('')
let drillFilter: { goodsId?: number; categoryId?: number; brandId?: number } = {}

function openDrill(row: GoodsRankRow, by: 'goods' | 'category' | 'brand') {
  drillFilter =
    by === 'goods'
      ? { goodsId: row.goodsId ?? undefined }
      : by === 'category'
        ? { categoryId: row.categoryId ?? undefined }
        : { brandId: row.brandId ?? undefined }
  const name = by === 'goods' ? row.goodsName : by === 'category' ? row.categoryName : row.brandName
  drillTitle.value = `下钻：${name ?? '—'} 销售单（${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}）`
  drillVisible.value = true
}

function fetchDrillPage(p: number, size: number) {
  return fetchDrillOrders('goods', { ...range, ...drillFilter, page: p, size })
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
        <AppFormItem label="视图">
          <AppRadioGroup v-model="view" @change="search">
            <AppRadioButton v-for="(label, key) in VIEW_LABELS" :key="key" :value="key">
              {{ label }}
            </AppRadioButton>
          </AppRadioGroup>
        </AppFormItem>
        <AppFormItem v-if="view === 'ranking'" label="排序">
          <AppSelect v-model="orderBy" style="width: 120px" @change="load">
            <AppOption label="销售额" value="salesAmount" />
            <AppOption label="销量" value="qty" />
            <AppOption label="毛利额" value="profitAmount" />
            <AppOption label="净销售额" value="netSalesAmount" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem v-if="view === 'slow-moving'" label="销量阈值">
          <AppInputNumber v-model="threshold" :min="0" :precision="0" style="width: 100px" />
        </AppFormItem>
        <AppFormItem v-if="view === 'ranking' || view === 'slow-moving'" label="分类">
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

    <!-- 品类结构饼图 -->
    <UCard v-if="view === 'category-structure'" class="chart-card">
      <template #header>
        <span>品类销售结构</span>
      </template>
      <div ref="pieChartRef" class="pie-chart" />
    </UCard>

    <!-- 数据表格 -->
    <UCard>
      <template #header>
        <span>{{ VIEW_LABELS[view] }}</span>
      </template>

      <AppTable :loading="loading" border :data="rows" stripe>
        <AppTableColumn type="index" label="#" width="50" align="center" />
        <AppTableColumn v-if="view !== 'category-structure' && view !== 'brand-sales'" label="商品编码" prop="goodsCode" width="120" />
        <AppTableColumn v-if="view !== 'category-structure' && view !== 'brand-sales'" label="商品名称" prop="goodsName" min-width="160" show-overflow-tooltip />
        <AppTableColumn v-if="view === 'category-structure'" label="分类" prop="categoryName" min-width="160" />
        <AppTableColumn v-if="view === 'brand-sales'" label="品牌" prop="brandName" min-width="160" />
        <AppTableColumn v-if="view !== 'brand-sales'" label="分类" prop="categoryName" width="120" show-overflow-tooltip />
        <AppTableColumn v-if="view === 'ranking' || view === 'brand-sales'" label="品牌" prop="brandName" width="100" show-overflow-tooltip />
        <AppTableColumn label="单数" prop="orderCount" width="70" align="right" />
        <AppTableColumn label="销量" width="80" align="right">
          <template #default="{ row }">{{ qty(row.qty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.salesAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="净销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.netSalesAmount) }}</template>
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
        <AppTableColumn v-if="view === 'slow-moving'" label="当前库存" width="90" align="right">
          <template #default="{ row }">{{ qty(row.stockQty) }}</template>
        </AppTableColumn>
        <AppTableColumn v-if="view === 'slow-moving'" label="占用资金" width="100" align="right">
          <template #default="{ row }">{{ money(row.occupiedAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn v-if="view === 'ranking'" label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton variant="link" @click="openDrill(row as GoodsRankRow, 'goods')">下钻</UButton>
          </template>
        </AppTableColumn>
        <AppTableColumn v-if="view === 'category-structure'" label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton v-if="row.categoryId" variant="link" @click="openDrill(row as GoodsRankRow, 'category')">
              下钻
            </UButton>
          </template>
        </AppTableColumn>
        <AppTableColumn v-if="view === 'brand-sales'" label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton v-if="row.brandId" variant="link" @click="openDrill(row as GoodsRankRow, 'brand')">
              下钻
            </UButton>
          </template>
        </AppTableColumn>
      </AppTable>

      <AppPagination
        v-if="view === 'ranking' || view === 'slow-moving'"
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="handlePageChange"
      />
    </UCard>

    <!-- 下钻单据列表（BR-RPT-06） -->
    <DrillOrderDrawer v-model="drillVisible" :title="drillTitle" :fetch-page="fetchDrillPage" />
  </div>
</template>

<style scoped lang="scss">
.page {
  .search-card,
  .chart-card {
    margin-bottom: 16px;
  }

  .presets {
    margin-top: 8px;
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }

  .pie-chart {
    height: 320px;
  }

  .pager {
    margin-top: 16px;
    justify-content: flex-end;
  }

  .profit {
    color: var(--el-color-success);
  }

  .loss {
    color: var(--el-color-danger);
  }
}
</style>
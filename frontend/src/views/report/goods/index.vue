<script setup lang="ts">
import { onMounted, reactive, ref, shallowRef } from 'vue'
import * as echarts from 'echarts'
import { getCategoryTree, type CategoryNode } from '@/api/goods'
import {
  exportGoodsReport,
  fetchBrandSales,
  fetchCategoryStructure,
  fetchDrillOrders,
  fetchGoodsRanking,
  fetchSlowMoving,
  recentDaysRange,
  thisMonthRange,
  todayRange,
  type DrillOrderRow,
  type GoodsRankRow,
  type ReportRange,
} from '@/api/report'

type ViewKey = 'ranking' | 'category-structure' | 'slow-moving' | 'brand-sales'

const VIEW_LABELS: Record<ViewKey, string> = {
  ranking: '商品销售排行',
  'category-structure': '品类销售结构',
  'slow-moving': '滞销商品清单',
  'brand-sales': '品牌销售分析',
}

const loading = ref(false)
const exporting = ref(false)
const view = ref<ViewKey>('ranking')
const initRange = thisMonthRange()
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
  return v == null ? '—' : Number(v).toFixed(2)
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

function setRange(preset: 'today' | 'month' | 'recent7' | 'recent30') {
  const r =
    preset === 'today'
      ? todayRange()
      : preset === 'month'
        ? thisMonthRange()
        : recentDaysRange(preset === 'recent7' ? 7 : 30)
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
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
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
    ElMessage.success('导出成功')
  } finally {
    exporting.value = false
  }
}

// ---------- 下钻（BR-RPT-06） ----------
const drillVisible = ref(false)
const drillLoading = ref(false)
const drillRows = ref<DrillOrderRow[]>([])
const drillTotal = ref(0)
const drillPage = ref(1)
const drillQuery = reactive({
  goodsId: undefined as number | undefined,
  goodsName: '',
})

async function openDrill(row: GoodsRankRow) {
  if (!row.goodsId) return
  drillQuery.goodsId = row.goodsId
  drillQuery.goodsName = row.goodsName ?? ''
  drillPage.value = 1
  drillVisible.value = true
  await loadDrill()
}

async function loadDrill() {
  drillLoading.value = true
  try {
    const data = await fetchDrillOrders(range, {
      goodsId: drillQuery.goodsId,
      page: drillPage.value,
      size: 20,
    })
    drillRows.value = data.list
    drillTotal.value = data.total
  } finally {
    drillLoading.value = false
  }
}

function handleDrillPageChange(p: number) {
  drillPage.value = p
  loadDrill()
}

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

onMounted(async () => {
  categoryTree.value = await getCategoryTree()
  load()
})
</script>

<template>
  <div class="page">
    <!-- 筛选区 -->
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="视图">
          <el-radio-group v-model="view" @change="search">
            <el-radio-button v-for="(label, key) in VIEW_LABELS" :key="key" :value="key">
              {{ label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="view === 'ranking'" label="排序">
          <el-select v-model="orderBy" style="width: 120px" @change="load">
            <el-option label="销售额" value="salesAmount" />
            <el-option label="销量" value="qty" />
            <el-option label="毛利额" value="profitAmount" />
            <el-option label="净销售额" value="netSalesAmount" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="view === 'slow-moving'" label="销量阈值">
          <el-input-number v-model="threshold" :min="0" :precision="0" style="width: 100px" />
        </el-form-item>
        <el-form-item v-if="view === 'ranking' || view === 'slow-moving'" label="分类">
          <el-tree-select
            v-model="categoryId"
            :data="categoryTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            check-strictly
            clearable
            placeholder="全部分类"
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item label="日期区间">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button :loading="exporting" @click="handleExport">导出</el-button>
        </el-form-item>
      </el-form>
      <div class="presets">
        <el-button size="small" @click="setRange('today')">今日</el-button>
        <el-button size="small" @click="setRange('month')">本月</el-button>
        <el-button size="small" @click="setRange('recent7')">近7日</el-button>
        <el-button size="small" @click="setRange('recent30')">近30日</el-button>
      </div>
    </el-card>

    <!-- 品类结构饼图 -->
    <el-card v-if="view === 'category-structure'" class="chart-card">
      <template #header>
        <span>品类销售结构</span>
      </template>
      <div ref="pieChartRef" class="pie-chart" />
    </el-card>

    <!-- 数据表格 -->
    <el-card>
      <template #header>
        <span>{{ VIEW_LABELS[view] }}</span>
      </template>

      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column v-if="view !== 'category-structure' && view !== 'brand-sales'" label="商品编码" prop="goodsCode" width="120" />
        <el-table-column v-if="view !== 'category-structure' && view !== 'brand-sales'" label="商品名称" prop="goodsName" min-width="160" show-overflow-tooltip />
        <el-table-column v-if="view === 'category-structure'" label="分类" prop="categoryName" min-width="160" />
        <el-table-column v-if="view === 'brand-sales'" label="品牌" prop="brandName" min-width="160" />
        <el-table-column v-if="view !== 'brand-sales'" label="分类" prop="categoryName" width="120" show-overflow-tooltip />
        <el-table-column v-if="view === 'ranking' || view === 'brand-sales'" label="品牌" prop="brandName" width="100" show-overflow-tooltip />
        <el-table-column label="单数" prop="orderCount" width="70" align="right" />
        <el-table-column label="销量" width="80" align="right">
          <template #default="{ row }">{{ qty(row.qty) }}</template>
        </el-table-column>
        <el-table-column label="销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.salesAmount) }}</template>
        </el-table-column>
        <el-table-column label="净销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.netSalesAmount) }}</template>
        </el-table-column>
        <el-table-column label="毛利额" width="100" align="right">
          <template #default="{ row }">
            <span :class="{ profit: Number(row.profitAmount ?? 0) >= 0, loss: Number(row.profitAmount ?? 0) < 0 }">
              {{ money(row.profitAmount) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="毛利率" width="80" align="right">
          <template #default="{ row }">{{ percent(row.profitRate) }}</template>
        </el-table-column>
        <el-table-column label="占比" width="80" align="right">
          <template #default="{ row }">{{ percent(row.shareRate) }}</template>
        </el-table-column>
        <el-table-column v-if="view === 'slow-moving'" label="当前库存" width="90" align="right">
          <template #default="{ row }">{{ qty(row.stockQty) }}</template>
        </el-table-column>
        <el-table-column v-if="view === 'slow-moving'" label="占用资金" width="100" align="right">
          <template #default="{ row }">{{ money(row.occupiedAmount) }}</template>
        </el-table-column>
        <el-table-column v-if="view === 'ranking'" label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDrill(row as GoodsRankRow)">下钻</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="view === 'ranking' || view === 'slow-moving'"
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- 下钻单据列表（BR-RPT-06） -->
    <el-drawer v-model="drillVisible" :title="`下钻：${drillQuery.goodsName}`" size="70%">
      <el-table v-loading="drillLoading" :data="drillRows" stripe size="small">
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
      </el-table>
      <el-pagination
        v-model:current-page="drillPage"
        :total="drillTotal"
        :page-size="20"
        layout="total, prev, pager, next"
        class="pager"
        @current-change="handleDrillPageChange"
      />
    </el-drawer>
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
    color: #67c23a;
  }

  .loss {
    color: #f56c6c;
  }
}
</style>
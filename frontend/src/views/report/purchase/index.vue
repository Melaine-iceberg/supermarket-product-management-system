<script setup lang="ts">
import { moneyText } from '@/utils/format'
import { onMounted, reactive, ref, shallowRef } from 'vue'
import * as echarts from 'echarts'
import { useRouter } from 'vue-router'
import { pageGoods, type GoodsListVO } from '@/api/goods'
import { PURCHASE_STATUS_MAP, SETTLE_STATUS_MAP } from '@/api/purchase'
import { useAuthStore } from '@/stores/auth'
import {
  dropEmptyColumns,
  pMoney,
  pPercent,
  printSheet,
  pQty,
  pTime,
  type PrintColumn,
} from '@/utils/print'
import {
  exportPurchaseReport,
  fetchArrival,
  fetchPriceTrend,
  fetchPurchaseDrillOrders,
  fetchPurchaseDrillReceipts,
  fetchPurchaseSummary,
  fetchSupplierRanking,
  recentDaysRange,
  thisMonthRange,
  type ArrivalRow,
  type DrillPurchaseRow,
  type DrillReceiptRow,
  type PriceTrendRow,
  type PurchSummaryRow,
  type ReportRange,
} from '@/api/report'
import type { DrillColumn } from '../components/drill'
import DrillTableDrawer from '../components/DrillTableDrawer.vue'

type ViewKey = 'summary-supplier' | 'summary-goods' | 'supplier-ranking' | 'price-trend' | 'arrival'

const VIEW_LABELS: Record<ViewKey, string> = {
  'summary-supplier': '采购汇总(供应商)',
  'summary-goods': '采购汇总(商品)',
  'supplier-ranking': '供应商排行',
  'price-trend': '价格波动',
  arrival: '到货及时率',
}

const loading = ref(false)
const exporting = ref(false)
const auth = useAuthStore()
const view = ref<ViewKey>('summary-supplier')
const initRange = thisMonthRange()
const range = reactive<ReportRange>({ ...initRange })
/** 日期选择器模型（el-date-picker 需要数组），查询前同步进 range */
const dateRange = ref<string[]>([initRange.beginDate, initRange.endDate])

// 数据容器
const summaryRows = ref<PurchSummaryRow[]>([])
const rankingRows = ref<PurchSummaryRow[]>([])
const arrivalRows = ref<ArrivalRow[]>([])
const priceRows = ref<PriceTrendRow[]>([])

// 商品选择（价格波动用）
const goodsOptions = ref<GoodsListVO[]>([])
const selectedGoodsId = ref<number>()
const goodsLoading = ref(false)

const trendChartRef = ref<HTMLDivElement>()
const trendChart = shallowRef<echarts.ECharts>()

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
}

function qty(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2)
}

function percent(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2) + '%'
}

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function syncRange() {
  range.beginDate = dateRange.value?.[0]
  range.endDate = dateRange.value?.[1]
}

function setRange(preset: 'month' | 'recent7' | 'recent30') {
  const r = preset === 'month' ? thisMonthRange() : recentDaysRange(preset === 'recent7' ? 7 : 30)
  dateRange.value = [r.beginDate, r.endDate]
  load()
}

async function load() {
  syncRange()
  loading.value = true
  try {
    if (view.value === 'summary-supplier') {
      summaryRows.value = await fetchPurchaseSummary(range, 'supplier')
    } else if (view.value === 'summary-goods') {
      summaryRows.value = await fetchPurchaseSummary(range, 'goods')
    } else if (view.value === 'supplier-ranking') {
      rankingRows.value = await fetchSupplierRanking(range)
    } else if (view.value === 'arrival') {
      arrivalRows.value = await fetchArrival(range)
    } else if (view.value === 'price-trend' && selectedGoodsId.value) {
      priceRows.value = await fetchPriceTrend(selectedGoodsId.value, range)
      renderTrend()
    }
  } finally {
    loading.value = false
  }
}

function renderTrend() {
  if (!trendChartRef.value || view.value !== 'price-trend') return
  if (!trendChart.value) {
    trendChart.value = echarts.init(trendChartRef.value)
  }
  trendChart.value.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 60, right: 30, top: 40, bottom: 40 },
    xAxis: {
      type: 'category',
      data: priceRows.value.map((r) => r.orderTime.slice(0, 10)),
      axisLabel: { fontSize: 11, rotate: 30 },
    },
    yAxis: { type: 'value', name: '单价(元)', axisLabel: { fontSize: 11 } },
    series: [
      {
        name: '采购单价',
        type: 'line',
        smooth: true,
        data: priceRows.value.map((r) => Number(r.price)),
        itemStyle: { color: '#409eff' },
        label: { show: true, position: 'top', fontSize: 10 },
      },
    ],
  })
}

function search() {
  load()
}

async function handleExport() {
  syncRange()
  exporting.value = true
  try {
    await exportPurchaseReport(view.value, range, { goodsId: selectedGoodsId.value })
    ElMessage.success('导出成功')
  } finally {
    exporting.value = false
  }
}

async function searchGoods(keyword: string) {
  if (!keyword || keyword.length < 1) return
  goodsLoading.value = true
  try {
    const data = await pageGoods({ name: keyword, page: 1, size: 20 })
    goodsOptions.value = data.list
  } finally {
    goodsLoading.value = false
  }
}

function handleGoodsChange() {
  if (view.value === 'price-trend') {
    load()
  }
}

// ---------- 下钻（BR-RPT-06）：汇总数字 → 构成它的采购单/入库单列表 ----------
const router = useRouter()

// 采购单下钻（供应商/商品汇总行、供应商排行行）
const drillOrderVisible = ref(false)
const drillOrderTitle = ref('')
let drillOrderFilter: { supplierId?: number; goodsId?: number } = {}

function openDrillOrders(filter: { supplierId?: number; goodsId?: number }, label: string) {
  drillOrderFilter = filter
  drillOrderTitle.value = `下钻：${label}（${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}）`
  drillOrderVisible.value = true
}

function fetchDrillOrdersPage(p: number, size: number) {
  return fetchPurchaseDrillOrders({ ...range, ...drillOrderFilter, page: p, size })
}

const drillOrderColumns: DrillColumn<DrillPurchaseRow>[] = [
  { label: '采购单号', width: 160, value: (r) => r.orderNo },
  { label: '供应商', minWidth: 150, value: (r) => r.supplierName },
  { label: '状态', width: 110, value: (r) => PURCHASE_STATUS_MAP[r.status]?.label ?? r.status },
  { label: '下单时间', width: 160, value: (r) => fmtTime(r.createTime) },
  { label: '预计到货', width: 100, value: (r) => r.expectDate },
  { label: '金额', width: 100, align: 'right', value: (r) => money(r.totalAmount) },
  { label: '已入库', width: 100, align: 'right', value: (r) => money(r.receivedAmount) },
  { label: '退货金额', width: 100, align: 'right', value: (r) => money(r.returnAmount) },
  { label: '结算状态', width: 100, value: (r) => SETTLE_STATUS_MAP[r.settleStatus] ?? r.settleStatus },
  { label: '已结算金额', width: 110, align: 'right', value: (r) => money(r.settleAmount) },
]

/** 打开采购单详情：采购单页支持 ?orderNo= 深链并自动弹出详情 */
function openDrillOrder(row: DrillPurchaseRow) {
  const { href } = router.resolve({ path: '/purchase/order', query: { orderNo: row.orderNo } })
  window.open(href, '_blank')
}

// 入库单下钻（到货及时率行）
const drillReceiptVisible = ref(false)
const drillReceiptTitle = ref('')
let drillReceiptSupplierId: number | undefined

function openDrillReceipts(row: ArrivalRow) {
  drillReceiptSupplierId = row.supplierId
  drillReceiptTitle.value = `下钻：${row.supplierName} 入库单（${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}）`
  drillReceiptVisible.value = true
}

function fetchDrillReceiptsPage(p: number, size: number) {
  return fetchPurchaseDrillReceipts({ ...range, supplierId: drillReceiptSupplierId, page: p, size })
}

const drillReceiptColumns: DrillColumn<DrillReceiptRow>[] = [
  { label: '入库单号', width: 160, value: (r) => r.receiptNo },
  { label: '原采购单', width: 160, value: (r) => r.purchaseOrderNo },
  { label: '供应商', minWidth: 150, value: (r) => r.supplierName },
  { label: '验收时间', width: 160, value: (r) => fmtTime(r.receiptTime) },
  { label: '验收人', width: 90, value: (r) => r.operatorName },
  { label: '送货单号', width: 120, value: (r) => r.deliveryOrderNo },
  { label: '金额', width: 100, align: 'right', value: (r) => money(r.totalAmount) },
  { label: '预计到货', width: 100, value: (r) => r.expectDate },
  { label: '按期', width: 80, align: 'center', value: (r) => (r.onTime == null ? null : r.onTime ? '按期' : '超期') },
]

/** 打开入库单详情：入库单页支持 ?orderNo= 深链并自动弹出详情 */
function openDrillReceipt(row: DrillReceiptRow) {
  const { href } = router.resolve({ path: '/purchase/receipt', query: { orderNo: row.receiptNo } })
  window.open(href, '_blank')
}

// ---------- 打印视图（FR-RPT-09）：列与后端 Excel 导出口径一致 ----------
const SUPPLIER_COLUMNS: PrintColumn<PurchSummaryRow>[] = [
  { label: '供应商', value: (r) => r.supplierName },
  { label: '单数', value: (r) => r.orderCount, align: 'right' },
  { label: '采购数量', value: (r) => pQty(r.purchaseQty), align: 'right' },
  { label: '采购金额', value: (r) => pMoney(r.purchaseAmount), align: 'right' },
  { label: '已入库数量', value: (r) => pQty(r.receivedQty), align: 'right' },
  { label: '在途数量', value: (r) => pQty(r.inTransitQty), align: 'right' },
  { label: '退货数量', value: (r) => pQty(r.returnQty), align: 'right' },
  { label: '退货金额', value: (r) => pMoney(r.returnAmount), align: 'right' },
  { label: '已结算金额', value: (r) => pMoney(r.settledAmount), align: 'right' },
  { label: '部分结算金额', value: (r) => pMoney(r.partialSettledAmount), align: 'right' },
  { label: '占比', value: (r) => pPercent(r.shareRate), align: 'right' },
]

const PURCH_GOODS_COLUMNS: PrintColumn<PurchSummaryRow>[] = [
  { label: '商品编码', value: (r) => r.goodsCode },
  { label: '商品名称', value: (r) => r.goodsName },
  { label: '分类', value: (r) => r.categoryName },
  { label: '单数', value: (r) => r.orderCount, align: 'right' },
  { label: '采购数量', value: (r) => pQty(r.purchaseQty), align: 'right' },
  { label: '采购金额', value: (r) => pMoney(r.purchaseAmount), align: 'right' },
  { label: '已入库数量', value: (r) => pQty(r.receivedQty), align: 'right' },
  { label: '在途数量', value: (r) => pQty(r.inTransitQty), align: 'right' },
  { label: '退货数量', value: (r) => pQty(r.returnQty), align: 'right' },
  { label: '退货金额', value: (r) => pMoney(r.returnAmount), align: 'right' },
  { label: '已结算金额', value: (r) => pMoney(r.settledAmount), align: 'right' },
  { label: '部分结算金额', value: (r) => pMoney(r.partialSettledAmount), align: 'right' },
]

async function handlePrint() {
  syncRange()
  const title = `采购报表 · ${VIEW_LABELS[view.value]}`
  const meta = [
    `统计区间：${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}`,
    `操作员：${auth.user?.realName ?? '—'}`,
  ]
  if (view.value === 'summary-supplier') {
    await printSheet({
      title,
      meta,
      columns: dropEmptyColumns(SUPPLIER_COLUMNS, summaryRows.value),
      rows: summaryRows.value,
      orientation: 'landscape',
    })
  } else if (view.value === 'summary-goods') {
    await printSheet({
      title,
      meta,
      columns: dropEmptyColumns(PURCH_GOODS_COLUMNS, summaryRows.value),
      rows: summaryRows.value,
      orientation: 'landscape',
    })
  } else if (view.value === 'supplier-ranking') {
    await printSheet({
      title,
      meta,
      columns: dropEmptyColumns(SUPPLIER_COLUMNS, rankingRows.value),
      rows: rankingRows.value,
      orientation: 'landscape',
    })
  } else if (view.value === 'price-trend') {
    const goodsName = goodsOptions.value.find((g) => g.id === selectedGoodsId.value)?.name
    await printSheet({
      title,
      meta: [...meta, `商品：${goodsName ?? '未选择'}`],
      columns: [
        { label: '采购单号', value: (r) => r.orderNo },
        { label: '供应商', value: (r) => r.supplierName },
        { label: '采购时间', value: (r) => pTime(r.orderTime) },
        { label: '数量', value: (r) => pQty(r.quantity), align: 'right' },
        { label: '单价', value: (r) => pMoney(r.price), align: 'right' },
      ],
      rows: priceRows.value,
    })
  } else {
    await printSheet({
      title,
      meta,
      columns: [
        { label: '供应商', value: (r) => r.supplierName },
        { label: '到货次数', value: (r) => r.receiptCount, align: 'right' },
        { label: '按期次数', value: (r) => r.onTimeCount, align: 'right' },
        { label: '到货及时率', value: (r) => pPercent(r.onTimeRate), align: 'right' },
      ],
      rows: arrivalRows.value,
    })
  }
}

onMounted(load)
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
        <el-form-item v-if="view === 'price-trend'" label="商品">
          <el-select
            v-model="selectedGoodsId"
            filterable
            remote
            reserve-keyword
            placeholder="搜索商品名称/编码"
            :remote-method="searchGoods"
            :loading="goodsLoading"
            style="width: 240px"
            @change="handleGoodsChange"
          >
            <el-option
              v-for="g in goodsOptions"
              :key="g.id"
              :label="`${g.goodsCode} - ${g.name}`"
              :value="g.id"
            />
          </el-select>
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
          <el-button @click="handlePrint">打印</el-button>
        </el-form-item>
      </el-form>
      <div class="presets">
        <el-button size="small" @click="setRange('month')">本月</el-button>
        <el-button size="small" @click="setRange('recent7')">近7日</el-button>
        <el-button size="small" @click="setRange('recent30')">近30日</el-button>
      </div>
    </el-card>

    <!-- 采购汇总（供应商/商品） -->
    <el-card v-if="view === 'summary-supplier' || view === 'summary-goods'">
      <template #header>
        <span>{{ view === 'summary-supplier' ? '采购汇总表（供应商维度）' : '采购汇总表（商品维度）' }}</span>
      </template>
      <el-table v-loading="loading" border :data="summaryRows" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column v-if="view === 'summary-supplier'" prop="supplierName" label="供应商" min-width="160" show-overflow-tooltip />
        <el-table-column v-if="view === 'summary-goods'" prop="goodsCode" label="商品编码" width="120" />
        <el-table-column v-if="view === 'summary-goods'" prop="goodsName" label="商品名称" min-width="160" show-overflow-tooltip />
        <el-table-column v-if="view === 'summary-goods'" prop="categoryName" label="分类" width="120" show-overflow-tooltip />
        <el-table-column label="单数" prop="orderCount" width="70" align="right" />
        <el-table-column label="采购数量" width="90" align="right">
          <template #default="{ row }">{{ qty(row.purchaseQty) }}</template>
        </el-table-column>
        <el-table-column label="采购金额" width="110" align="right">
          <template #default="{ row }">{{ money(row.purchaseAmount) }}</template>
        </el-table-column>
        <el-table-column label="已入库" width="90" align="right">
          <template #default="{ row }">{{ qty(row.receivedQty) }}</template>
        </el-table-column>
        <el-table-column label="在途" width="80" align="right">
          <template #default="{ row }">{{ qty(row.inTransitQty) }}</template>
        </el-table-column>
        <el-table-column label="退货数量" width="90" align="right">
          <template #default="{ row }">{{ qty(row.returnQty) }}</template>
        </el-table-column>
        <el-table-column label="退货金额" width="100" align="right">
          <template #default="{ row }">{{ money(row.returnAmount) }}</template>
        </el-table-column>
        <el-table-column label="已结算" width="100" align="right">
          <template #default="{ row }">{{ money(row.settledAmount) }}</template>
        </el-table-column>
        <el-table-column label="部分结算" width="100" align="right">
          <template #default="{ row }">{{ money(row.partialSettledAmount) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="view === 'summary-supplier'"
              link
              type="primary"
              @click="openDrillOrders({ supplierId: row.supplierId ?? undefined }, `供应商 ${row.supplierName}`)"
            >
              下钻
            </el-button>
            <el-button
              v-else
              link
              type="primary"
              @click="openDrillOrders({ goodsId: row.goodsId ?? undefined }, `商品 ${row.goodsName}`)"
            >
              下钻
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 供应商排行 -->
    <el-card v-if="view === 'supplier-ranking'">
      <template #header><span>供应商采购排行</span></template>
      <el-table v-loading="loading" border :data="rankingRows" stripe>
        <el-table-column type="index" label="排名" width="60" align="center" />
        <el-table-column prop="supplierName" label="供应商" min-width="180" show-overflow-tooltip />
        <el-table-column label="单数" prop="orderCount" width="70" align="right" />
        <el-table-column label="采购数量" width="100" align="right">
          <template #default="{ row }">{{ qty(row.purchaseQty) }}</template>
        </el-table-column>
        <el-table-column label="采购金额" width="120" align="right">
          <template #default="{ row }">
            <b>{{ money(row.purchaseAmount) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="占比" width="90" align="right">
          <template #default="{ row }">{{ percent(row.shareRate) }}</template>
        </el-table-column>
        <el-table-column label="已入库" width="90" align="right">
          <template #default="{ row }">{{ qty(row.receivedQty) }}</template>
        </el-table-column>
        <el-table-column label="在途" width="80" align="right">
          <template #default="{ row }">{{ qty(row.inTransitQty) }}</template>
        </el-table-column>
        <el-table-column label="退货金额" width="100" align="right">
          <template #default="{ row }">{{ money(row.returnAmount) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="openDrillOrders({ supplierId: row.supplierId ?? undefined }, `供应商 ${row.supplierName}`)"
            >
              下钻
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 价格波动 -->
    <el-card v-if="view === 'price-trend'">
      <template #header><span>采购价格波动分析</span></template>
      <el-alert
        v-if="!selectedGoodsId"
        title="请先选择商品"
        type="info"
        :closable="false"
        show-icon
        class="mb-16"
      />
      <template v-else>
        <div ref="trendChartRef" class="trend-chart" />
        <el-table v-loading="loading" border :data="priceRows" stripe class="mt-16">
          <el-table-column type="index" label="#" width="50" align="center" />
          <el-table-column prop="orderNo" label="采购单号" width="160" />
          <el-table-column prop="supplierName" label="供应商" min-width="160" show-overflow-tooltip />
          <el-table-column label="采购时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.orderTime) }}</template>
          </el-table-column>
          <el-table-column label="数量" width="90" align="right">
            <template #default="{ row }">{{ qty(row.quantity) }}</template>
          </el-table-column>
          <el-table-column label="单价" width="100" align="right">
            <template #default="{ row }">
              <b>{{ money(row.price) }}</b>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <!-- 到货及时率 -->
    <el-card v-if="view === 'arrival'">
      <template #header><span>到货及时率统计</span></template>
      <el-table v-loading="loading" border :data="arrivalRows" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="supplierName" label="供应商" min-width="180" show-overflow-tooltip />
        <el-table-column label="到货次数" prop="receiptCount" width="100" align="right" />
        <el-table-column label="按期次数" prop="onTimeCount" width="100" align="right" />
        <el-table-column label="及时率" width="100" align="right">
          <template #default="{ row }">
            <span :class="{ success: Number(row.onTimeRate ?? 0) >= 90, warning: Number(row.onTimeRate ?? 0) < 90 }">
              {{ percent(row.onTimeRate) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDrillReceipts(row as ArrivalRow)">下钻</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 下钻单据列表（BR-RPT-06） -->
    <DrillTableDrawer
      v-model="drillOrderVisible"
      :title="drillOrderTitle"
      :columns="drillOrderColumns"
      :fetch-page="fetchDrillOrdersPage"
    >
      <template #actions="{ row }">
        <el-button link type="primary" @click="openDrillOrder(row as DrillPurchaseRow)">查看</el-button>
      </template>
    </DrillTableDrawer>
    <DrillTableDrawer
      v-model="drillReceiptVisible"
      :title="drillReceiptTitle"
      :columns="drillReceiptColumns"
      :fetch-page="fetchDrillReceiptsPage"
    >
      <template #actions="{ row }">
        <el-button link type="primary" @click="openDrillReceipt(row as DrillReceiptRow)">查看</el-button>
      </template>
    </DrillTableDrawer>
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

  .trend-chart {
    height: 280px;
  }

  .mb-16 {
    margin-bottom: 16px;
  }

  .mt-16 {
    margin-top: 16px;
  }

  .success {
    color: #67c23a;
  }

  .warning {
    color: #e6a23c;
  }
}
</style>
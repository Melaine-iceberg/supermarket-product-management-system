<script setup lang="ts">
import { notify } from '@/utils/notify'
import { moneyText } from '@/utils/format'
import { onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { dropEmptyColumns, pMoney, pPercent, printSheet, type PrintColumn } from '@/utils/print'
import {
  exportSalesReport,
  fetchDrillOrders,
  fetchSalesByCashier,
  fetchSalesByCounter,
  fetchSalesByHour,
  fetchSalesByPayMethod,
  fetchSalesDaily,
  fetchSalesMemberRatio,
  todayRange,
  type DrillOrderFilters,
  type ReportRange,
  type SalesReportRow,
  type SalesReportVO,
} from '@/api/report'
import { getDictItems } from '@/api/system'
import type { EChartsOption } from 'echarts'
import DrillOrderDrawer from '../components/DrillOrderDrawer.vue'
import {
  axisCommon,
  chartBase,
  chartTokens,
  legendCommon,
  tooltipCommon,
  compactNumber,
} from '../components/chartOptions'
import { useReportChart } from '../components/useReportChart'
import { RANGE_PRESETS, presetRange, type RangePreset } from '../components/rangePresets'

type ViewKey = 'daily' | 'pay-method' | 'hour' | 'cashier' | 'counter' | 'member-ratio'

const VIEW_LABELS: Record<ViewKey, string> = {
  daily: '销售日报',
  'pay-method': '收款方式',
  hour: '时段分布',
  cashier: '收银员业绩',
  counter: '收银台业绩',
  'member-ratio': '会员占比',
}

const loading = ref(false)
const exporting = ref(false)
const auth = useAuthStore()
const view = ref<ViewKey>('daily')
const monthly = ref(false)
const initRange = todayRange()
const range = reactive<ReportRange>({ ...initRange })
/** 日期选择器模型（el-date-picker 需要数组），查询前同步进 range */
const dateRange = ref<string[]>([initRange.beginDate, initRange.endDate])
const rows = ref<SalesReportRow[]>([])
const total = ref<SalesReportRow | null>(null)
const chartRef = ref<HTMLDivElement>()
const { render: renderChart, dispose: disposeChart } = useReportChart(chartRef)

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
}

function percent(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2) + '%'
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
    if (view.value === 'daily') {
      const vo: SalesReportVO = await fetchSalesDaily(range, monthly.value)
      rows.value = vo.rows
      total.value = vo.total
    } else if (view.value === 'pay-method') {
      rows.value = await fetchSalesByPayMethod(range)
      total.value = null
    } else if (view.value === 'hour') {
      rows.value = await fetchSalesByHour(range)
      total.value = null
    } else if (view.value === 'cashier') {
      rows.value = await fetchSalesByCashier(range)
      total.value = null
    } else if (view.value === 'counter') {
      rows.value = await fetchSalesByCounter(range)
      total.value = null
    } else {
      rows.value = await fetchSalesMemberRatio(range)
      total.value = null
    }
    await renderSalesChart()
  } finally {
    loading.value = false
  }
}

// ---------- 统计图（趋势 / 构成 / 对比） ----------
const CHART_TITLES: Record<ViewKey, string> = {
  daily: '销售趋势（净销售 / 订单数）',
  'pay-method': '收款方式构成（净销售）',
  hour: '时段分布（净销售）',
  cashier: '收银员业绩 TOP10（净销售）',
  counter: '收银台业绩（净销售）',
  'member-ratio': '会员 / 散客净销售占比',
}

function netAmountOf(row: SalesReportRow) {
  return compactNumber(row.netAmount ?? row.salesAmount)
}

function renderSalesChart() {
  if (!rows.value.length) {
    disposeChart()
    return Promise.resolve()
  }
  return renderChart(buildSalesOption)
}

function buildSalesOption(): EChartsOption {
  if (view.value === 'daily') return buildDailyOption()
  if (view.value === 'hour') return buildHourOption()
  if (view.value === 'cashier' || view.value === 'counter') {
    return buildRankBarOption(rows.value, '净销售')
  }
  const valueName = '净销售'
  return buildPieOption(
    rows.value.map((row) => ({ name: row.groupKey, value: netAmountOf(row) })),
    valueName,
  )
}

function buildDailyOption(): EChartsOption {
  const t = chartTokens()
  return {
    ...chartBase(),
    tooltip: tooltipCommon('axis'),
    legend: { ...legendCommon(), top: 0, data: ['净销售', '订单数'] },
    grid: { left: 12, right: 16, top: 44, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: rows.value.map((row) => (monthly.value ? row.groupKey : row.groupKey.slice(5))),
      ...axisCommon(),
      splitLine: { show: false },
    },
    yAxis: [
      { type: 'value', name: '金额(元)', ...axisCommon() },
      { type: 'value', name: '单数', ...axisCommon(), splitLine: { show: false } },
    ],
    series: [
      {
        name: '净销售',
        type: 'line',
        smooth: true,
        symbolSize: 6,
        data: rows.value.map(netAmountOf),
        itemStyle: { color: t.primary },
        areaStyle: { color: t.primary, opacity: 0.12 },
      },
      {
        name: '订单数',
        type: 'bar',
        yAxisIndex: 1,
        barMaxWidth: 18,
        data: rows.value.map((row) => row.orderCount),
        itemStyle: { color: t.success },
      },
    ],
  }
}

function buildHourOption(): EChartsOption {
  const t = chartTokens()
  return {
    ...chartBase(),
    tooltip: tooltipCommon('axis'),
    grid: { left: 12, right: 16, top: 24, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: rows.value.map((row) => `${row.groupKey.padStart(2, '0')}:00`),
      ...axisCommon(),
      splitLine: { show: false },
    },
    yAxis: { type: 'value', name: '金额(元)', ...axisCommon() },
    series: [
      {
        name: '净销售',
        type: 'bar',
        barMaxWidth: 22,
        data: rows.value.map(netAmountOf),
        itemStyle: { color: t.primary, borderRadius: [4, 4, 0, 0] },
      },
    ],
  }
}

function buildRankBarOption(source: SalesReportRow[], valueName: string): EChartsOption {
  const t = chartTokens()
  const sorted = [...source].sort((a, b) => netAmountOf(b) - netAmountOf(a)).slice(0, 10)
  return {
    ...chartBase(),
    tooltip: tooltipCommon('axis'),
    grid: { left: 12, right: 44, top: 16, bottom: 8, containLabel: true },
    xAxis: { type: 'value', ...axisCommon() },
    yAxis: {
      type: 'category',
      inverse: true,
      data: sorted.map((row) => row.groupKey),
      ...axisCommon(),
      splitLine: { show: false },
    },
    series: [
      {
        name: valueName,
        type: 'bar',
        barMaxWidth: 18,
        data: sorted.map(netAmountOf),
        itemStyle: { color: t.primary, borderRadius: [0, 4, 4, 0] },
        label: { show: true, position: 'right', color: t.muted, fontSize: 11 },
      },
    ],
  }
}

function buildPieOption(data: { name: string; value: number }[], valueName: string): EChartsOption {
  const t = chartTokens()
  return {
    ...chartBase(),
    tooltip: { ...tooltipCommon('item'), formatter: '{b}: {c} ({d}%)' },
    legend: { ...legendCommon(), bottom: 0 },
    series: [
      {
        name: valueName,
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '45%'],
        itemStyle: { borderColor: t.bg, borderWidth: 2, borderRadius: 4 },
        label: { color: t.muted, formatter: '{b}\n{d}%' },
        data,
      },
    ],
  }
}

function search() {
  load()
}

async function handleExport() {
  syncRange()
  exporting.value = true
  try {
    await exportSalesReport(view.value, range, monthly.value)
    notify.success('导出成功')
  } finally {
    exporting.value = false
  }
}

// ---------- 打印视图（FR-RPT-09，A4 横向：14 列指标过宽） ----------
const PRINT_FIRST_LABEL: Record<ViewKey, string> = {
  daily: '日期',
  'pay-method': '收款方式',
  hour: '时段',
  cashier: '收银员',
  counter: '收银台',
  'member-ratio': '单据类型',
}

const PRINT_COLUMNS: PrintColumn<SalesReportRow>[] = [
  { label: '单数', value: (r) => r.orderCount, align: 'right' },
  { label: '件数', value: (r) => r.itemCount, align: 'right' },
  { label: '销售额', value: (r) => pMoney(r.salesAmount), align: 'right' },
  { label: '优惠额', value: (r) => pMoney(r.discountAmount), align: 'right' },
  { label: '净销售额', value: (r) => pMoney(r.netSalesAmount), align: 'right' },
  { label: '退货额', value: (r) => pMoney(r.refundAmount), align: 'right' },
  { label: '退货单数', value: (r) => r.returnCount, align: 'right' },
  { label: '净销售', value: (r) => pMoney(r.netAmount), align: 'right' },
  { label: '销售成本', value: (r) => pMoney(r.costAmount), align: 'right' },
  { label: '毛利额', value: (r) => pMoney(r.profitAmount), align: 'right' },
  { label: '毛利率', value: (r) => pPercent(r.profitRate), align: 'right' },
  { label: '客单价', value: (r) => pMoney(r.avgOrderAmount), align: 'right' },
  { label: '占比', value: (r) => pPercent(r.shareRate), align: 'right' },
]

async function handlePrint() {
  syncRange()
  const monthlyLabel = monthly.value && view.value === 'daily' ? '（月报）' : ''
  const first: PrintColumn<SalesReportRow> = {
    label: view.value === 'daily' && monthly.value ? '月份' : PRINT_FIRST_LABEL[view.value],
    value: (r) => r.groupKey,
  }
  const t = total.value
  await printSheet({
    title: `销售报表 · ${VIEW_LABELS[view.value]}${monthlyLabel}`,
    meta: [
      `统计区间：${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}`,
      `操作员：${auth.user?.realName ?? '—'}`,
    ],
    columns: dropEmptyColumns([first, ...PRINT_COLUMNS], rows.value),
    rows: rows.value,
    totals: t
      ? [
          `合计单数：${t.orderCount}`,
          `销售额：￥${pMoney(t.salesAmount)}`,
          `净销售：￥${pMoney(t.netAmount)}`,
          `毛利额：￥${pMoney(t.profitAmount)}`,
          `毛利率：${pPercent(t.profitRate)}`,
        ]
      : [],
    orientation: 'landscape',
  })
}

// ---------- 下钻（BR-RPT-06）：汇总数字 → 构成它的销售单列表（AC-16） ----------
/** 收款方式名称→编码兜底（字典未加载时）；GAP-P1-09：优先按数据字典 pay_type 反查 */
const FALLBACK_PAY_LABEL_TO_CODE: Record<string, string> = {
  现金: 'CASH',
  银行卡: 'BANK',
  移动支付: 'MOBILE',
  会员储值: 'BALANCE',
}

/** 数据字典 pay_type：名称→编码（报表展示名由后端按字典回填，下钻反查编码走字典，NFR-EXT-01） */
const payCodeByLabel = ref<Record<string, string>>({ ...FALLBACK_PAY_LABEL_TO_CODE })

async function loadPayTypes() {
  try {
    const items = await getDictItems('pay_type')
    if (items.length) {
      payCodeByLabel.value = Object.fromEntries(items.map((i) => [i.label, i.value]))
    }
  } catch {
    // 回退内置映射
  }
}

const drillVisible = ref(false)
const drillTitle = ref('')
let drillFilters: DrillOrderFilters = {}

function openDrill(filters: DrillOrderFilters, label: string) {
  drillFilters = filters
  drillTitle.value = `下钻：${label}`
  drillVisible.value = true
}

function fetchDrillPage(p: number, size: number) {
  return fetchDrillOrders('sales', { ...drillFilters, page: p, size })
}

/** 报表行 → 下钻筛选（口径与行一致；日报按月行取整月区间） */
function drillRow(row: SalesReportRow) {
  syncRange()
  if (view.value === 'daily') {
    if (monthly.value) {
      const [y, m] = row.groupKey.split('-').map(Number)
      const last = new Date(y, m, 0).getDate()
      openDrill(
        {
          beginDate: `${row.groupKey}-01`,
          endDate: `${row.groupKey}-${String(last).padStart(2, '0')}`,
        },
        `销售月报 ${row.groupKey}`,
      )
    } else {
      const day = (row.bizDate ?? row.groupKey).slice(0, 10)
      openDrill({ beginDate: day, endDate: day }, `销售日报 ${day}`)
    }
  } else if (view.value === 'pay-method') {
    const code = payCodeByLabel.value[row.groupKey] ?? FALLBACK_PAY_LABEL_TO_CODE[row.groupKey]
    if (!code) {
      notify.warning('该收款方式暂不支持下钻')
      return
    }
    openDrill({ payType: code, ...range }, `收款方式 ${row.groupKey}`)
  } else if (view.value === 'hour') {
    openDrill({ hour: parseInt(row.groupKey), ...range }, `时段 ${row.groupKey}`)
  } else if (view.value === 'cashier') {
    openDrill({ cashierName: row.groupKey, ...range }, `收银员 ${row.groupKey}`)
  } else if (view.value === 'counter') {
    openDrill({ counterNo: parseInt(row.groupKey), ...range }, `收银台 ${row.groupKey}`)
  } else {
    openDrill({ memberOnly: row.groupKey === '会员单', ...range }, `单据类型 ${row.groupKey}`)
  }
}

/** 合计行下钻：整个查询区间的销售单 */
function drillTotalRow() {
  syncRange()
  openDrill({ ...range }, `合计（${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}）`)
}

onMounted(() => {
  loadPayTypes()
  load()
})
</script>

<template>
  <div class="page">
    <!-- 筛选区 -->
    <UCard class="search-card">
      <AppForm inline @submit.prevent>
        <AppFormItem label="视图" label-as="span">
          <AppRadioGroup v-model="view" @change="load">
            <AppRadioButton v-for="(label, key) in VIEW_LABELS" :key="key" :value="key">
              {{ label }}
            </AppRadioButton>
          </AppRadioGroup>
        </AppFormItem>
        <AppFormItem v-if="view === 'daily'" label="粒度">
          <USwitch v-model="monthly" @change="load" />
          <span class="ml-2 text-sm text-muted">{{ monthly ? '月报' : '日报' }}</span>
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
            <UButton :loading="exporting" color="neutral" variant="outline" @click="handleExport"
              >导出</UButton
            >
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

    <!-- 统计图：趋势 / 构成 / 对比 -->
    <UCard v-if="rows.length" class="chart-card">
      <template #header
        ><span>{{ CHART_TITLES[view] }}</span></template
      >
      <div ref="chartRef" class="report-chart" />
    </UCard>

    <!-- 数据表格 -->
    <UCard>
      <template #header>
        <span>{{ VIEW_LABELS[view] }}{{ monthly && view === 'daily' ? '（月报）' : '' }}</span>
      </template>

      <AppTable :loading="loading" border :data="rows" stripe>
        <AppTableColumn
          :label="view === 'daily' ? (monthly ? '月份' : '日期') : '分组'"
          prop="groupKey"
          width="120"
          fixed="left"
        />
        <AppTableColumn label="单数" prop="orderCount" width="70" align="right" />
        <AppTableColumn label="件数" width="70" align="right">
          <template #default="{ row }">{{ row.itemCount ?? '—' }}</template>
        </AppTableColumn>
        <AppTableColumn label="销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.salesAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="优惠额" width="90" align="right">
          <template #default="{ row }">{{ money(row.discountAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="净销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.netSalesAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="退货额" width="90" align="right">
          <template #default="{ row }">{{ money(row.refundAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="退货单数" width="80" align="right">
          <template #default="{ row }">{{ row.returnCount ?? '—' }}</template>
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
            <span
              :class="{
                profit: Number(row.profitAmount ?? 0) >= 0,
                loss: Number(row.profitAmount ?? 0) < 0,
              }"
            >
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
        <AppTableColumn label="占比" width="80" align="right">
          <template #default="{ row }">{{ percent(row.shareRate) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton variant="link" @click="drillRow(row as SalesReportRow)">下钻</UButton>
          </template>
        </AppTableColumn>
      </AppTable>

      <!-- 合计行 -->
      <div v-if="total" class="total-row">
        <span class="total-label">合计</span>
        <span>单数: {{ total.orderCount }}</span>
        <span>销售额: ￥{{ money(total.salesAmount) }}</span>
        <span>净销售: ￥{{ money(total.netAmount) }}</span>
        <span>毛利额: ￥{{ money(total.profitAmount) }}</span>
        <span>毛利率: {{ percent(total.profitRate) }}</span>
        <UButton variant="link" class="total-drill" @click="drillTotalRow">下钻</UButton>
      </div>
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

  .chart-card {
    margin-bottom: 16px;
  }

  .report-chart {
    width: 100%;
    height: 320px;
  }

  .presets {
    margin-top: 8px;
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
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

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { dropEmptyColumns, pMoney, pPercent, printSheet, type PrintColumn } from '@/utils/print'
import {
  exportSalesReport,
  fetchSalesByCashier,
  fetchSalesByCounter,
  fetchSalesByHour,
  fetchSalesByPayMethod,
  fetchSalesDaily,
  fetchSalesMemberRatio,
  lastMonthRange,
  recentDaysRange,
  thisMonthRange,
  thisWeekRange,
  todayRange,
  yesterdayRange,
  type ReportRange,
  type SalesReportRow,
  type SalesReportVO,
} from '@/api/report'

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

function money(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2)
}

function percent(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2) + '%'
}

function syncRange() {
  range.beginDate = dateRange.value?.[0]
  range.endDate = dateRange.value?.[1]
}

function setRange(preset: 'today' | 'yesterday' | 'week' | 'month' | 'lastMonth' | 'recent7' | 'recent30') {
  const r =
    preset === 'today'
      ? todayRange()
      : preset === 'yesterday'
        ? yesterdayRange()
        : preset === 'week'
          ? thisWeekRange()
          : preset === 'month'
            ? thisMonthRange()
            : preset === 'lastMonth'
              ? lastMonthRange()
              : recentDaysRange(preset === 'recent7' ? 7 : 30)
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
  } finally {
    loading.value = false
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
    ElMessage.success('导出成功')
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

onMounted(load)
</script>

<template>
  <div class="page">
    <!-- 筛选区 -->
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="视图">
          <el-radio-group v-model="view" @change="load">
            <el-radio-button v-for="(label, key) in VIEW_LABELS" :key="key" :value="key">
              {{ label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="view === 'daily'" label="粒度">
          <el-switch v-model="monthly" active-text="月报" inactive-text="日报" @change="load" />
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
        <el-button size="small" @click="setRange('today')">今日</el-button>
        <el-button size="small" @click="setRange('yesterday')">昨日</el-button>
        <el-button size="small" @click="setRange('week')">本周</el-button>
        <el-button size="small" @click="setRange('month')">本月</el-button>
        <el-button size="small" @click="setRange('lastMonth')">上月</el-button>
        <el-button size="small" @click="setRange('recent7')">近7日</el-button>
        <el-button size="small" @click="setRange('recent30')">近30日</el-button>
      </div>
    </el-card>

    <!-- 数据表格 -->
    <el-card>
      <template #header>
        <span>{{ VIEW_LABELS[view] }}{{ monthly && view === 'daily' ? '（月报）' : '' }}</span>
      </template>

      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column
          :label="view === 'daily' ? (monthly ? '月份' : '日期') : '分组'"
          prop="groupKey"
          width="120"
          fixed="left"
        />
        <el-table-column label="单数" prop="orderCount" width="70" align="right" />
        <el-table-column label="件数" width="70" align="right">
          <template #default="{ row }">{{ row.itemCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.salesAmount) }}</template>
        </el-table-column>
        <el-table-column label="优惠额" width="90" align="right">
          <template #default="{ row }">{{ money(row.discountAmount) }}</template>
        </el-table-column>
        <el-table-column label="净销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.netSalesAmount) }}</template>
        </el-table-column>
        <el-table-column label="退货额" width="90" align="right">
          <template #default="{ row }">{{ money(row.refundAmount) }}</template>
        </el-table-column>
        <el-table-column label="退货单数" width="80" align="right">
          <template #default="{ row }">{{ row.returnCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="净销售" width="100" align="right">
          <template #default="{ row }">
            <b>{{ money(row.netAmount) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="销售成本" width="100" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
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
        <el-table-column label="客单价" width="90" align="right">
          <template #default="{ row }">{{ money(row.avgOrderAmount) }}</template>
        </el-table-column>
        <el-table-column label="占比" width="80" align="right">
          <template #default="{ row }">{{ percent(row.shareRate) }}</template>
        </el-table-column>
      </el-table>

      <!-- 合计行 -->
      <div v-if="total" class="total-row">
        <span class="total-label">合计</span>
        <span>单数: {{ total.orderCount }}</span>
        <span>销售额: ￥{{ money(total.salesAmount) }}</span>
        <span>净销售: ￥{{ money(total.netAmount) }}</span>
        <span>毛利额: ￥{{ money(total.profitAmount) }}</span>
        <span>毛利率: {{ percent(total.profitRate) }}</span>
      </div>
    </el-card>
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

  .total-row {
    display: flex;
    gap: 24px;
    padding: 12px 16px;
    background: #f5f7fa;
    border-top: 1px solid #ebeef5;
    font-size: 13px;
    font-weight: 600;

    .total-label {
      color: #409eff;
    }
  }

  .profit {
    color: #67c23a;
  }

  .loss {
    color: #f56c6c;
  }
}
</style>
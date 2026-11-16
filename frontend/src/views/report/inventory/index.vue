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
  pTime,
  type PrintColumn,
} from '@/utils/print'
import {
  CHANGE_TYPE_MAP,
  exportInventoryReport,
  fetchInventoryDrillLoss,
  fetchInventoryDrillRecords,
  fetchInvChanges,
  fetchInvSummaryByCategory,
  fetchInvSummaryByGoods,
  fetchInvWarnings,
  fetchLossReport,
  fetchTurnoverByCategory,
  fetchTurnoverByGoods,
  STOCK_STATUS_MAP,
  todayRange,
  type DrillInvRecordRow,
  type DrillLossRow,
  type InvChangeRow,
  type InvSummaryRow,
  type LossGoodsRow,
  type LossReportVO,
  type ReportRange,
  type TurnoverRow,
  type WarningRptRow,
} from '@/api/report'
import type { DrillColumn } from '../components/drill'
import DrillTableDrawer from '../components/DrillTableDrawer.vue'
import { RANGE_PRESETS, presetRange, type RangePreset } from '../components/rangePresets'

type ViewKey = 'summary-goods' | 'summary-category' | 'warnings' | 'changes' | 'turnover-goods' | 'turnover-category' | 'loss'

const VIEW_LABELS: Record<ViewKey, string> = {
  'summary-goods': '库存汇总(商品)',
  'summary-category': '库存汇总(分类)',
  warnings: '库存预警',
  changes: '变动明细',
  'turnover-goods': '周转率(商品)',
  'turnover-category': '周转率(分类)',
  loss: '损耗报表',
}

const loading = ref(false)
const exporting = ref(false)
const auth = useAuthStore()
const view = ref<ViewKey>('summary-goods')
const initRange = todayRange()
const range = reactive<ReportRange>({ ...initRange })
/** 日期选择器模型（el-date-picker 需要数组），查询前同步进 range */
const dateRange = ref<string[]>([initRange.beginDate, initRange.endDate])
const categoryId = ref<number>()
const alertType = ref<string>()

const categoryTree = ref<CategoryNode[]>([])

// 数据容器
const summaryRows = ref<InvSummaryRow[]>([])
const summaryTotal = ref(0)
const warningRows = ref<WarningRptRow[]>([])
const warningTotal = ref(0)
const changeRows = ref<InvChangeRow[]>([])
const turnoverRows = ref<TurnoverRow[]>([])
const turnoverTotal = ref(0)
const lossData = ref<LossReportVO | null>(null)

const page = ref(1)
const size = ref(20)

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

/** Element tag type → Nuxt UI badge color（danger→error、info→neutral） */
function tagColor(type?: string): 'success' | 'warning' | 'error' | 'neutral' {
  if (type === 'danger') return 'error'
  if (type === 'success' || type === 'warning') return type
  return 'neutral'
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
    if (view.value === 'summary-goods') {
      const data = await fetchInvSummaryByGoods(categoryId.value, page.value, size.value)
      summaryRows.value = data.list
      summaryTotal.value = data.total
    } else if (view.value === 'summary-category') {
      summaryRows.value = await fetchInvSummaryByCategory()
      summaryTotal.value = summaryRows.value.length
    } else if (view.value === 'warnings') {
      const data = await fetchInvWarnings(alertType.value, page.value, size.value)
      warningRows.value = data.list
      warningTotal.value = data.total
    } else if (view.value === 'changes') {
      changeRows.value = await fetchInvChanges(range)
    } else if (view.value === 'turnover-goods') {
      const data = await fetchTurnoverByGoods(range, categoryId.value, page.value, size.value)
      turnoverRows.value = data.list
      turnoverTotal.value = data.total
    } else if (view.value === 'turnover-category') {
      turnoverRows.value = await fetchTurnoverByCategory(range, categoryId.value)
      turnoverTotal.value = turnoverRows.value.length
    } else {
      lossData.value = await fetchLossReport(range)
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
    await exportInventoryReport(view.value, range, {
      alertType: alertType.value,
      categoryId: categoryId.value,
    })
    notify.success('导出成功')
  } finally {
    exporting.value = false
  }
}

// ---------- 打印视图（FR-RPT-09）：列与后端 Excel 导出口径一致 ----------
const ALERT_LABEL: Record<WarningRptRow['alertType'], string> = {
  OUT: '缺货',
  WARNING: '预警',
  OVERSTOCK: '积压',
}

const SUMMARY_COLUMNS: PrintColumn<InvSummaryRow>[] = [
  { label: '分类', value: (r) => r.categoryName },
  { label: '商品编码', value: (r) => r.goodsCode },
  { label: '商品名称', value: (r) => r.goodsName },
  { label: '商品数', value: (r) => r.goodsCount, align: 'right' },
  { label: '结存数量', value: (r) => pQty(r.stockQty), align: 'right' },
  { label: '成本单价', value: (r) => pMoney(r.costPrice), align: 'right' },
  { label: '库存成本金额', value: (r) => pMoney(r.costAmount), align: 'right' },
  { label: '零售价值', value: (r) => pMoney(r.retailValue), align: 'right' },
  { label: '库存状态', value: (r) => STOCK_STATUS_MAP[r.stockStatus ?? '']?.label ?? r.stockStatus, align: 'center' },
]

const WARNING_COLUMNS: PrintColumn<WarningRptRow>[] = [
  { label: '商品编码', value: (r) => r.goodsCode },
  { label: '商品名称', value: (r) => r.goodsName },
  { label: '分类', value: (r) => r.categoryName },
  { label: '预警类型', value: (r) => ALERT_LABEL[r.alertType], align: 'center' },
  { label: '结存数量', value: (r) => pQty(r.stockQty), align: 'right' },
  { label: '安全库存下限', value: (r) => pQty(r.safetyStockLower), align: 'right' },
  { label: '库存上限', value: (r) => pQty(r.safetyStockUpper), align: 'right' },
  { label: '缺口量', value: (r) => pQty(r.gapQty), align: 'right' },
  { label: '建议采购量', value: (r) => pQty(r.suggestQty), align: 'right' },
  { label: '最近变动时间', value: (r) => pTime(r.lastChangeTime) },
]

const TURNOVER_COLUMNS: PrintColumn<TurnoverRow>[] = [
  { label: '商品编码', value: (r) => r.goodsCode },
  { label: '商品名称', value: (r) => r.goodsName },
  { label: '分类', value: (r) => r.categoryName },
  { label: '结存数量', value: (r) => pQty(r.stockQty), align: 'right' },
  { label: '期末库存金额', value: (r) => pMoney(r.costAmount), align: 'right' },
  { label: '期初库存金额', value: (r) => pMoney(r.beginAmount), align: 'right' },
  { label: '平均库存金额', value: (r) => pMoney(r.avgAmount), align: 'right' },
  { label: '销售成本', value: (r) => pMoney(r.saleCostAmount), align: 'right' },
  { label: '退货成本回冲', value: (r) => pMoney(r.returnCostAmount), align: 'right' },
  { label: '周转率', value: (r) => pQty(r.turnoverRate), align: 'right' },
  { label: '周转天数', value: (r) => pQty(r.turnoverDays), align: 'right' },
]

async function handlePrint() {
  syncRange()
  const title = `库存报表 · ${VIEW_LABELS[view.value]}`
  // 汇总与预警是时点快照，只有变动/周转/损耗按区间统计
  const ranged = view.value === 'changes' || view.value.startsWith('turnover') || view.value === 'loss'
  const meta = [
    ranged ? `统计区间：${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}` : null,
    `操作员：${auth.user?.realName ?? '—'}`,
  ]
  if (view.value === 'summary-goods' || view.value === 'summary-category') {
    await printSheet({
      title,
      meta: [...meta, `第 ${page.value} 页 / 共 ${summaryTotal.value} 条`],
      columns: dropEmptyColumns(SUMMARY_COLUMNS, summaryRows.value),
      rows: summaryRows.value,
      orientation: 'landscape',
    })
  } else if (view.value === 'warnings') {
    await printSheet({
      title,
      meta: [...meta, `第 ${page.value} 页 / 共 ${warningTotal.value} 条`],
      columns: WARNING_COLUMNS,
      rows: warningRows.value,
      orientation: 'landscape',
    })
  } else if (view.value === 'changes') {
    await printSheet({
      title,
      meta,
      columns: [
        { label: '变动类型', value: (r) => CHANGE_TYPE_MAP[r.recordType] ?? r.recordType },
        { label: '单据数', value: (r) => r.recordCount, align: 'right' },
        { label: '变动数量', value: (r) => pQty(r.changeQty), align: 'right' },
        { label: '金额', value: (r) => pMoney(r.amount), align: 'right' },
      ],
      rows: changeRows.value,
    })
  } else if (view.value === 'turnover-goods' || view.value === 'turnover-category') {
    await printSheet({
      title,
      meta,
      columns: dropEmptyColumns(TURNOVER_COLUMNS, turnoverRows.value),
      rows: turnoverRows.value,
      orientation: 'landscape',
    })
  } else {
    const vo = lossData.value
    await printSheet({
      title,
      meta,
      columns: [
        { label: '商品名称', value: (r) => r.goodsName },
        { label: '分类', value: (r) => r.categoryName },
        { label: '报损数量', value: (r) => pQty(r.lossQty), align: 'right' },
        { label: '报损金额', value: (r) => pMoney(r.lossAmount), align: 'right' },
        { label: '盘亏数量', value: (r) => pQty(r.checkQty), align: 'right' },
        { label: '盘亏金额', value: (r) => pMoney(r.checkAmount), align: 'right' },
        { label: '损耗金额', value: (r) => pMoney(r.totalAmount), align: 'right' },
      ],
      rows: vo?.rows ?? [],
      totals: vo
        ? [
            `损耗金额合计：￥${pMoney(vo.totalLossAmount)}`,
            `报溢金额合计：￥${pMoney(vo.totalOverflowAmount)}`,
            `期初库存金额：￥${pMoney(vo.beginInventoryAmount)}`,
            `损耗率：${pPercent(vo.lossRate)}`,
          ]
        : [],
      orientation: 'landscape',
    })
  }
}

// ---------- 下钻（BR-RPT-06）：汇总数字 → 构成它的库存流水/损耗单据 ----------
// 库存流水下钻（变动明细/周转行、库存汇总/预警行 → 库存流水）
const drillRecordVisible = ref(false)
const drillRecordTitle = ref('')
let drillRecordFilter: { recordType?: string; goodsId?: number; categoryId?: number } = {}
/** true：实时结存类汇总（库存汇总/预警）下钻全量流水，不限营业日区间 */
let drillRecordAllDates = false

function openDrillRecords(
  filter: { recordType?: string; goodsId?: number; categoryId?: number },
  label: string,
  allDates = false,
) {
  drillRecordFilter = filter
  drillRecordAllDates = allDates
  const windowText = allDates
    ? '全部流水'
    : `${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}`
  drillRecordTitle.value = `下钻：${label}（${windowText}）`
  drillRecordVisible.value = true
}

function fetchDrillRecordsPage(p: number, size: number) {
  const dates = drillRecordAllDates ? {} : { ...range }
  return fetchInventoryDrillRecords({ ...dates, ...drillRecordFilter, page: p, size })
}

const drillRecordColumns: DrillColumn<DrillInvRecordRow>[] = [
  { label: '商品编码', width: 120, value: (r) => r.goodsCode },
  { label: '商品名称', minWidth: 140, value: (r) => r.goodsName },
  { label: '变动类型', width: 100, value: (r) => CHANGE_TYPE_MAP[r.recordType] ?? r.recordType },
  { label: '变动数量', width: 100, align: 'right', value: (r) => qty(r.changeQty) },
  { label: '变动前', width: 90, align: 'right', value: (r) => qty(r.beforeQty) },
  { label: '变动后', width: 90, align: 'right', value: (r) => qty(r.afterQty) },
  { label: '成本单价', width: 90, align: 'right', value: (r) => money(r.unitCost) },
  { label: '来源单据', width: 160, value: (r) => r.bizNo },
  { label: '操作员', width: 90, value: (r) => r.operatorName },
  { label: '操作时间', width: 160, value: (r) => fmtTime(r.operateTime) },
  { label: '备注', minWidth: 120, value: (r) => r.remark },
]

// 损耗单据下钻（损耗报表行 → 报损单 / 盘亏差异）
const drillLossVisible = ref(false)
const drillLossTitle = ref('')
let drillLossFilter: { kind: 'loss' | 'check'; goodsId?: number } = { kind: 'loss' }

function openDrillLoss(row: LossGoodsRow, kind: 'loss' | 'check') {
  drillLossFilter = { kind, goodsId: row.goodsId }
  drillLossTitle.value = `下钻：${row.goodsName} ${kind === 'loss' ? '报损单' : '盘亏差异'}（${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}）`
  drillLossVisible.value = true
}

function fetchDrillLossPage(p: number, size: number) {
  return fetchInventoryDrillLoss({ ...range, ...drillLossFilter, page: p, size })
}

const drillLossColumns: DrillColumn<DrillLossRow>[] = [
  { label: '单据号', width: 160, value: (r) => r.orderNo },
  { label: '类型', width: 80, align: 'center', value: (r) => (r.kind === 'LOSS' ? '报损' : '盘亏') },
  { label: '商品名称', minWidth: 140, value: (r) => r.goodsName },
  { label: '数量', width: 90, align: 'right', value: (r) => qty(r.qty) },
  { label: '成本单价', width: 90, align: 'right', value: (r) => money(r.costPrice) },
  { label: '金额', width: 100, align: 'right', value: (r) => money(r.amount) },
  { label: '原因', minWidth: 140, value: (r) => r.reason },
  { label: '制单人', width: 90, value: (r) => r.applyName },
  { label: '审核人', width: 90, value: (r) => r.auditorName },
  { label: '业务时间', width: 160, value: (r) => fmtTime(r.bizTime) },
]

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
        <AppFormItem v-if="view === 'warnings'" label="预警类型">
          <AppSelect v-model="alertType" clearable placeholder="全部" style="width: 110px" @change="load">
            <AppOption label="缺货" value="OUT" />
            <AppOption label="预警" value="WARNING" />
            <AppOption label="积压" value="OVERSTOCK" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem v-if="view.includes('summary') || view.includes('turnover')" label="分类">
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
        <AppFormItem v-if="view === 'changes' || view.includes('turnover') || view === 'loss'" label="日期区间">
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
      <div v-if="view === 'changes' || view.includes('turnover') || view === 'loss'" class="presets">
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

    <!-- 库存汇总（商品维度） -->
    <UCard v-if="view === 'summary-goods'">
      <template #header><span>库存汇总表（商品维度）</span></template>
      <AppTable :loading="loading" border :data="summaryRows" stripe>
        <AppTableColumn type="index" label="#" width="50" align="center" />
        <AppTableColumn prop="categoryName" label="分类" width="120" show-overflow-tooltip />
        <AppTableColumn prop="goodsCode" label="商品编码" width="120" />
        <AppTableColumn prop="goodsName" label="商品名称" min-width="160" show-overflow-tooltip />
        <AppTableColumn label="结存数量" width="90" align="right">
          <template #default="{ row }">{{ qty(row.stockQty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="成本单价" width="90" align="right">
          <template #default="{ row }">{{ money(row.costPrice) }}</template>
        </AppTableColumn>
        <AppTableColumn label="库存成本" width="100" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="零售价值" width="100" align="right">
          <template #default="{ row }">{{ money(row.retailValue) }}</template>
        </AppTableColumn>
        <AppTableColumn label="状态" width="80" align="center">
          <template #default="{ row }">
            <UBadge :color="tagColor(STOCK_STATUS_MAP[row.stockStatus]?.type)" variant="soft" size="sm">
              {{ STOCK_STATUS_MAP[row.stockStatus]?.label ?? row.stockStatus }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton
              variant="link"
              size="sm"
              @click="openDrillRecords({ goodsId: row.goodsId }, `商品 ${row.goodsName} 库存流水`, true)"
            >
              流水
            </UButton>
          </template>
        </AppTableColumn>
      </AppTable>
      <AppPagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="summaryTotal"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="handlePageChange"
      />
    </UCard>

    <!-- 库存汇总（分类维度） -->
    <UCard v-if="view === 'summary-category'">
      <template #header><span>库存汇总表（分类维度）</span></template>
      <AppTable :loading="loading" border :data="summaryRows" stripe>
        <AppTableColumn type="index" label="#" width="50" align="center" />
        <AppTableColumn prop="categoryName" label="分类" min-width="160" />
        <AppTableColumn prop="goodsCount" label="商品数" width="80" align="right" />
        <AppTableColumn label="结存数量" width="100" align="right">
          <template #default="{ row }">{{ qty(row.stockQty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="库存成本" width="120" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="零售价值" width="120" align="right">
          <template #default="{ row }">{{ money(row.retailValue) }}</template>
        </AppTableColumn>
      </AppTable>
    </UCard>

    <!-- 库存预警 -->
    <UCard v-if="view === 'warnings'">
      <template #header><span>库存预警报表</span></template>
      <AppTable :loading="loading" border :data="warningRows" stripe>
        <AppTableColumn type="index" label="#" width="50" align="center" />
        <AppTableColumn prop="goodsCode" label="商品编码" width="120" />
        <AppTableColumn prop="goodsName" label="商品名称" min-width="160" show-overflow-tooltip />
        <AppTableColumn prop="categoryName" label="分类" width="120" show-overflow-tooltip />
        <AppTableColumn label="预警类型" width="90" align="center">
          <template #default="{ row }">
            <UBadge
              :color="row.alertType === 'OUT' ? 'error' : row.alertType === 'WARNING' ? 'warning' : 'neutral'"
              variant="soft"
              size="sm"
            >
              {{ row.alertType === 'OUT' ? '缺货' : row.alertType === 'WARNING' ? '预警' : '积压' }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="结存" width="80" align="right">
          <template #default="{ row }">{{ qty(row.stockQty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="下限" width="80" align="right">
          <template #default="{ row }">{{ qty(row.safetyStockLower) }}</template>
        </AppTableColumn>
        <AppTableColumn label="上限" width="80" align="right">
          <template #default="{ row }">{{ qty(row.safetyStockUpper) }}</template>
        </AppTableColumn>
        <AppTableColumn label="缺口" width="80" align="right">
          <template #default="{ row }">{{ qty(row.gapQty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="建议采购" width="90" align="right">
          <template #default="{ row }">{{ qty(row.suggestQty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="最近变动" width="160">
          <template #default="{ row }">{{ fmtTime(row.lastChangeTime) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton
              variant="link"
              size="sm"
              @click="openDrillRecords({ goodsId: row.goodsId }, `商品 ${row.goodsName} 库存流水`, true)"
            >
              流水
            </UButton>
          </template>
        </AppTableColumn>
      </AppTable>
      <AppPagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="warningTotal"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="handlePageChange"
      />
    </UCard>

    <!-- 变动明细 -->
    <UCard v-if="view === 'changes'">
      <template #header><span>库存变动明细汇总</span></template>
      <AppTable :loading="loading" border :data="changeRows" stripe>
        <AppTableColumn label="变动类型" min-width="140">
          <template #default="{ row }">{{ CHANGE_TYPE_MAP[row.recordType] ?? row.recordType }}</template>
        </AppTableColumn>
        <AppTableColumn prop="recordCount" label="单据数" width="100" align="right" />
        <AppTableColumn label="变动数量" width="120" align="right">
          <template #default="{ row }">{{ qty(row.changeQty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="金额" width="120" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton variant="link" size="sm" @click="openDrillRecords({ recordType: row.recordType }, '变动类型 ' + (CHANGE_TYPE_MAP[row.recordType] ?? row.recordType))">
              明细
            </UButton>
          </template>
        </AppTableColumn>
      </AppTable>
    </UCard>

    <!-- 周转率（商品） -->
    <UCard v-if="view === 'turnover-goods'">
      <template #header><span>库存周转率（商品维度）</span></template>
      <AppTable :loading="loading" border :data="turnoverRows" stripe>
        <AppTableColumn type="index" label="#" width="50" align="center" />
        <AppTableColumn prop="goodsCode" label="商品编码" width="120" />
        <AppTableColumn prop="goodsName" label="商品名称" min-width="160" show-overflow-tooltip />
        <AppTableColumn prop="categoryName" label="分类" width="120" show-overflow-tooltip />
        <AppTableColumn label="结存" width="80" align="right">
          <template #default="{ row }">{{ qty(row.stockQty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="期末成本" width="100" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="期初成本" width="100" align="right">
          <template #default="{ row }">{{ money(row.beginAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="平均库存" width="100" align="right">
          <template #default="{ row }">{{ money(row.avgAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="销售成本" width="100" align="right">
          <template #default="{ row }">{{ money(row.saleCostAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="周转率" width="80" align="right">
          <template #default="{ row }">{{ qty(row.turnoverRate) }}</template>
        </AppTableColumn>
        <AppTableColumn label="周转天数" width="90" align="right">
          <template #default="{ row }">{{ qty(row.turnoverDays) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <UButton
              v-if="row.goodsId != null"
              variant="link"
              size="sm"
              @click="openDrillRecords({ recordType: 'SALE_OUT', goodsId: row.goodsId }, '商品 ' + row.goodsName + ' 销售出库流水')"
            >
              销售流水
            </UButton>
          </template>
        </AppTableColumn>
      </AppTable>
      <AppPagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="turnoverTotal"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="handlePageChange"
      />
    </UCard>

    <!-- 周转率（分类） -->
    <UCard v-if="view === 'turnover-category'">
      <template #header><span>库存周转率（分类维度）</span></template>
      <AppTable :loading="loading" border :data="turnoverRows" stripe>
        <AppTableColumn type="index" label="#" width="50" align="center" />
        <AppTableColumn prop="categoryName" label="分类" min-width="160" />
        <AppTableColumn label="结存" width="100" align="right">
          <template #default="{ row }">{{ qty(row.stockQty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="期末成本" width="120" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="平均库存" width="120" align="right">
          <template #default="{ row }">{{ money(row.avgAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="销售成本" width="120" align="right">
          <template #default="{ row }">{{ money(row.saleCostAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="周转率" width="90" align="right">
          <template #default="{ row }">{{ qty(row.turnoverRate) }}</template>
        </AppTableColumn>
        <AppTableColumn label="周转天数" width="100" align="right">
          <template #default="{ row }">{{ qty(row.turnoverDays) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <UButton
              v-if="row.categoryId != null"
              variant="link"
              size="sm"
              @click="
                openDrillRecords(
                  { recordType: 'SALE_OUT', categoryId: row.categoryId },
                  `分类 ${row.categoryName ?? ''} 销售出库流水`,
                )
              "
            >
              销售流水
            </UButton>
          </template>
        </AppTableColumn>
      </AppTable>
    </UCard>

    <!-- 损耗报表 -->
    <UCard v-if="view === 'loss'">
      <template #header><span>损耗报表</span></template>
      <div v-if="lossData" class="loss-summary">
        <AppDescriptions :column="4" border size="small">
          <AppDescriptionsItem label="损耗金额">￥{{ money(lossData.totalLossAmount) }}</AppDescriptionsItem>
          <AppDescriptionsItem label="报溢金额">￥{{ money(lossData.totalOverflowAmount) }}</AppDescriptionsItem>
          <AppDescriptionsItem label="期初库存金额">￥{{ money(lossData.beginInventoryAmount) }}</AppDescriptionsItem>
          <AppDescriptionsItem label="损耗率">{{ percent(lossData.lossRate) }}</AppDescriptionsItem>
        </AppDescriptions>
      </div>
      <AppTable :loading="loading" border :data="lossData?.rows ?? []" stripe class="loss-table">
        <AppTableColumn type="index" label="#" width="50" align="center" />
        <AppTableColumn prop="goodsName" label="商品名称" min-width="160" show-overflow-tooltip />
        <AppTableColumn prop="categoryName" label="分类" width="120" show-overflow-tooltip />
        <AppTableColumn label="报损数量" width="90" align="right">
          <template #default="{ row }">
            <UButton v-if="row.lossQty > 0" variant="link" size="sm" @click="openDrillLoss(row as LossGoodsRow, 'loss')">
              {{ qty(row.lossQty) }}
            </UButton>
            <span v-else>{{ qty(row.lossQty) }}</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="报损金额" width="100" align="right">
          <template #default="{ row }">{{ money(row.lossAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="盘亏数量" width="90" align="right">
          <template #default="{ row }">
            <UButton v-if="row.checkQty > 0" variant="link" size="sm" @click="openDrillLoss(row as LossGoodsRow, 'check')">
              {{ qty(row.checkQty) }}
            </UButton>
            <span v-else>{{ qty(row.checkQty) }}</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="盘亏金额" width="100" align="right">
          <template #default="{ row }">{{ money(row.checkAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="损耗合计" width="100" align="right">
          <template #default="{ row }">
            <b class="loss-amount">{{ money(row.totalAmount) }}</b>
          </template>
        </AppTableColumn>
      </AppTable>
    </UCard>

    <!-- 下钻抽屉（BR-RPT-06） -->
    <DrillTableDrawer
      v-model="drillRecordVisible"
      :title="drillRecordTitle"
      :columns="drillRecordColumns"
      :fetch-page="fetchDrillRecordsPage"
    />
    <DrillTableDrawer
      v-model="drillLossVisible"
      :title="drillLossTitle"
      :columns="drillLossColumns"
      :fetch-page="fetchDrillLossPage"
    />
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

  .loss-summary {
    margin-bottom: 16px;
  }

  .loss-table {
    margin-top: 0;
  }

  .loss-amount {
    color: var(--el-color-danger);
  }
}
</style>
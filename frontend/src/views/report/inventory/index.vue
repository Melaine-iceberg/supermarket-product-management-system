<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { getCategoryTree, type CategoryNode } from '@/api/goods'
import {
  CHANGE_TYPE_MAP,
  exportInventoryReport,
  fetchInvChanges,
  fetchInvSummaryByCategory,
  fetchInvSummaryByGoods,
  fetchInvWarnings,
  fetchLossReport,
  fetchTurnoverByCategory,
  fetchTurnoverByGoods,
  recentDaysRange,
  STOCK_STATUS_MAP,
  thisMonthRange,
  type InvChangeRow,
  type InvSummaryRow,
  type LossReportVO,
  type ReportRange,
  type TurnoverRow,
  type WarningRptRow,
} from '@/api/report'

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
const view = ref<ViewKey>('summary-goods')
const initRange = thisMonthRange()
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
  return v == null ? '—' : Number(v).toFixed(2)
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
    ElMessage.success('导出成功')
  } finally {
    exporting.value = false
  }
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
        <el-form-item v-if="view === 'warnings'" label="预警类型">
          <el-select v-model="alertType" clearable placeholder="全部" style="width: 110px" @change="load">
            <el-option label="缺货" value="OUT" />
            <el-option label="预警" value="WARNING" />
            <el-option label="积压" value="OVERSTOCK" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="view.includes('summary') || view.includes('turnover')" label="分类">
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
        <el-form-item v-if="view === 'changes' || view.includes('turnover') || view === 'loss'" label="日期区间">
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
      <div v-if="view === 'changes' || view.includes('turnover') || view === 'loss'" class="presets">
        <el-button size="small" @click="setRange('month')">本月</el-button>
        <el-button size="small" @click="setRange('recent7')">近7日</el-button>
        <el-button size="small" @click="setRange('recent30')">近30日</el-button>
      </div>
    </el-card>

    <!-- 库存汇总（商品维度） -->
    <el-card v-if="view === 'summary-goods'">
      <template #header><span>库存汇总表（商品维度）</span></template>
      <el-table v-loading="loading" :data="summaryRows" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="categoryName" label="分类" width="120" show-overflow-tooltip />
        <el-table-column prop="goodsCode" label="商品编码" width="120" />
        <el-table-column prop="goodsName" label="商品名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="结存数量" width="90" align="right">
          <template #default="{ row }">{{ qty(row.stockQty) }}</template>
        </el-table-column>
        <el-table-column label="成本单价" width="90" align="right">
          <template #default="{ row }">{{ money(row.costPrice) }}</template>
        </el-table-column>
        <el-table-column label="库存成本" width="100" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </el-table-column>
        <el-table-column label="零售价值" width="100" align="right">
          <template #default="{ row }">{{ money(row.retailValue) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="STOCK_STATUS_MAP[row.stockStatus]?.type ?? 'info'" size="small">
              {{ STOCK_STATUS_MAP[row.stockStatus]?.label ?? row.stockStatus }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="summaryTotal"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- 库存汇总（分类维度） -->
    <el-card v-if="view === 'summary-category'">
      <template #header><span>库存汇总表（分类维度）</span></template>
      <el-table v-loading="loading" :data="summaryRows" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="categoryName" label="分类" min-width="160" />
        <el-table-column prop="goodsCount" label="商品数" width="80" align="right" />
        <el-table-column label="结存数量" width="100" align="right">
          <template #default="{ row }">{{ qty(row.stockQty) }}</template>
        </el-table-column>
        <el-table-column label="库存成本" width="120" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </el-table-column>
        <el-table-column label="零售价值" width="120" align="right">
          <template #default="{ row }">{{ money(row.retailValue) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 库存预警 -->
    <el-card v-if="view === 'warnings'">
      <template #header><span>库存预警报表</span></template>
      <el-table v-loading="loading" :data="warningRows" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="goodsCode" label="商品编码" width="120" />
        <el-table-column prop="goodsName" label="商品名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="categoryName" label="分类" width="120" show-overflow-tooltip />
        <el-table-column label="预警类型" width="90" align="center">
          <template #default="{ row }">
            <el-tag
              :type="row.alertType === 'OUT' ? 'danger' : row.alertType === 'WARNING' ? 'warning' : 'info'"
              size="small"
            >
              {{ row.alertType === 'OUT' ? '缺货' : row.alertType === 'WARNING' ? '预警' : '积压' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结存" width="80" align="right">
          <template #default="{ row }">{{ qty(row.stockQty) }}</template>
        </el-table-column>
        <el-table-column label="下限" width="80" align="right">
          <template #default="{ row }">{{ qty(row.safetyStockLower) }}</template>
        </el-table-column>
        <el-table-column label="上限" width="80" align="right">
          <template #default="{ row }">{{ qty(row.safetyStockUpper) }}</template>
        </el-table-column>
        <el-table-column label="缺口" width="80" align="right">
          <template #default="{ row }">{{ qty(row.gapQty) }}</template>
        </el-table-column>
        <el-table-column label="建议采购" width="90" align="right">
          <template #default="{ row }">{{ qty(row.suggestQty) }}</template>
        </el-table-column>
        <el-table-column label="最近变动" width="160">
          <template #default="{ row }">{{ fmtTime(row.lastChangeTime) }}</template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="warningTotal"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- 变动明细 -->
    <el-card v-if="view === 'changes'">
      <template #header><span>库存变动明细汇总</span></template>
      <el-table v-loading="loading" :data="changeRows" stripe>
        <el-table-column label="变动类型" min-width="140">
          <template #default="{ row }">{{ CHANGE_TYPE_MAP[row.recordType] ?? row.recordType }}</template>
        </el-table-column>
        <el-table-column prop="recordCount" label="单据数" width="100" align="right" />
        <el-table-column label="变动数量" width="120" align="right">
          <template #default="{ row }">{{ qty(row.changeQty) }}</template>
        </el-table-column>
        <el-table-column label="金额" width="120" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 周转率（商品） -->
    <el-card v-if="view === 'turnover-goods'">
      <template #header><span>库存周转率（商品维度）</span></template>
      <el-table v-loading="loading" :data="turnoverRows" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="goodsCode" label="商品编码" width="120" />
        <el-table-column prop="goodsName" label="商品名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="categoryName" label="分类" width="120" show-overflow-tooltip />
        <el-table-column label="结存" width="80" align="right">
          <template #default="{ row }">{{ qty(row.stockQty) }}</template>
        </el-table-column>
        <el-table-column label="期末成本" width="100" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </el-table-column>
        <el-table-column label="期初成本" width="100" align="right">
          <template #default="{ row }">{{ money(row.beginAmount) }}</template>
        </el-table-column>
        <el-table-column label="平均库存" width="100" align="right">
          <template #default="{ row }">{{ money(row.avgAmount) }}</template>
        </el-table-column>
        <el-table-column label="销售成本" width="100" align="right">
          <template #default="{ row }">{{ money(row.saleCostAmount) }}</template>
        </el-table-column>
        <el-table-column label="周转率" width="80" align="right">
          <template #default="{ row }">{{ qty(row.turnoverRate) }}</template>
        </el-table-column>
        <el-table-column label="周转天数" width="90" align="right">
          <template #default="{ row }">{{ qty(row.turnoverDays) }}</template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="turnoverTotal"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- 周转率（分类） -->
    <el-card v-if="view === 'turnover-category'">
      <template #header><span>库存周转率（分类维度）</span></template>
      <el-table v-loading="loading" :data="turnoverRows" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="categoryName" label="分类" min-width="160" />
        <el-table-column label="结存" width="100" align="right">
          <template #default="{ row }">{{ qty(row.stockQty) }}</template>
        </el-table-column>
        <el-table-column label="期末成本" width="120" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </el-table-column>
        <el-table-column label="平均库存" width="120" align="right">
          <template #default="{ row }">{{ money(row.avgAmount) }}</template>
        </el-table-column>
        <el-table-column label="销售成本" width="120" align="right">
          <template #default="{ row }">{{ money(row.saleCostAmount) }}</template>
        </el-table-column>
        <el-table-column label="周转率" width="90" align="right">
          <template #default="{ row }">{{ qty(row.turnoverRate) }}</template>
        </el-table-column>
        <el-table-column label="周转天数" width="100" align="right">
          <template #default="{ row }">{{ qty(row.turnoverDays) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 损耗报表 -->
    <el-card v-if="view === 'loss'">
      <template #header><span>损耗报表</span></template>
      <div v-if="lossData" class="loss-summary">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="损耗金额">￥{{ money(lossData.totalLossAmount) }}</el-descriptions-item>
          <el-descriptions-item label="报溢金额">￥{{ money(lossData.totalOverflowAmount) }}</el-descriptions-item>
          <el-descriptions-item label="期初库存金额">￥{{ money(lossData.beginInventoryAmount) }}</el-descriptions-item>
          <el-descriptions-item label="损耗率">{{ percent(lossData.lossRate) }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <el-table v-loading="loading" :data="lossData?.rows ?? []" stripe class="loss-table">
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="goodsName" label="商品名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="categoryName" label="分类" width="120" show-overflow-tooltip />
        <el-table-column label="报损数量" width="90" align="right">
          <template #default="{ row }">{{ qty(row.lossQty) }}</template>
        </el-table-column>
        <el-table-column label="报损金额" width="100" align="right">
          <template #default="{ row }">{{ money(row.lossAmount) }}</template>
        </el-table-column>
        <el-table-column label="盘亏数量" width="90" align="right">
          <template #default="{ row }">{{ qty(row.checkQty) }}</template>
        </el-table-column>
        <el-table-column label="盘亏金额" width="100" align="right">
          <template #default="{ row }">{{ money(row.checkAmount) }}</template>
        </el-table-column>
        <el-table-column label="损耗合计" width="100" align="right">
          <template #default="{ row }">
            <b class="loss-amount">{{ money(row.totalAmount) }}</b>
          </template>
        </el-table-column>
      </el-table>
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
    color: #f56c6c;
  }
}
</style>
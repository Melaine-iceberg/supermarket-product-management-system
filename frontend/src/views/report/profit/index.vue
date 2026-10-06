<script setup lang="ts">
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
  recentDaysRange,
  thisMonthRange,
  type DrillOrderFilters,
  type GoodsRankRow,
  type NegativeProfitRow,
  type ReportRange,
  type SalesReportRow,
  type SalesReportVO,
} from '@/api/report'
import DrillOrderDrawer from '../components/DrillOrderDrawer.vue'

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
const initRange = thisMonthRange()
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

function setRange(preset: 'month' | 'recent7' | 'recent30') {
  const r = preset === 'month' ? thisMonthRange() : recentDaysRange(preset === 'recent7' ? 7 : 30)
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
    ElMessage.success('导出成功')
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
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="维度">
          <el-radio-group v-model="view" @change="search">
            <el-radio-button v-for="(label, key) in VIEW_LABELS" :key="key" :value="key">
              {{ label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="view === 'by-goods'" label="分类">
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
          <el-button @click="handlePrint">打印</el-button>
        </el-form-item>
      </el-form>
      <div class="presets">
        <el-button size="small" @click="setRange('month')">本月</el-button>
        <el-button size="small" @click="setRange('recent7')">近7日</el-button>
        <el-button size="small" @click="setRange('recent30')">近30日</el-button>
      </div>
    </el-card>

    <!-- 按商品毛利 -->
    <el-card v-if="view === 'by-goods'">
      <template #header><span>商品毛利分析</span></template>
      <el-table v-loading="loading" :data="goodsRows" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="goodsCode" label="商品编码" width="120" />
        <el-table-column prop="goodsName" label="商品名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="categoryName" label="分类" width="120" show-overflow-tooltip />
        <el-table-column prop="brandName" label="品牌" width="100" show-overflow-tooltip />
        <el-table-column label="单数" prop="orderCount" width="70" align="right" />
        <el-table-column label="销量" width="80" align="right">
          <template #default="{ row }">{{ qty(row.qty) }}</template>
        </el-table-column>
        <el-table-column label="净销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.netSalesAmount) }}</template>
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
        <el-table-column label="占比" width="80" align="right">
          <template #default="{ row }">{{ percent(row.shareRate) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="drillGoodsRow(row as GoodsRankRow)">下钻</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="goodsTotal"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- 按分类毛利 -->
    <el-card v-if="view === 'by-category'">
      <template #header><span>分类毛利分析</span></template>
      <el-table v-loading="loading" :data="categoryRows" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="categoryName" label="分类" min-width="160" />
        <el-table-column label="单数" prop="orderCount" width="80" align="right" />
        <el-table-column label="销量" width="90" align="right">
          <template #default="{ row }">{{ qty(row.qty) }}</template>
        </el-table-column>
        <el-table-column label="销售额" width="110" align="right">
          <template #default="{ row }">{{ money(row.salesAmount) }}</template>
        </el-table-column>
        <el-table-column label="净销售额" width="110" align="right">
          <template #default="{ row }">{{ money(row.netSalesAmount) }}</template>
        </el-table-column>
        <el-table-column label="销售成本" width="110" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </el-table-column>
        <el-table-column label="毛利额" width="110" align="right">
          <template #default="{ row }">
            <span :class="{ profit: Number(row.profitAmount ?? 0) >= 0, loss: Number(row.profitAmount ?? 0) < 0 }">
              {{ money(row.profitAmount) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="毛利率" width="90" align="right">
          <template #default="{ row }">{{ percent(row.profitRate) }}</template>
        </el-table-column>
        <el-table-column label="占比" width="80" align="right">
          <template #default="{ row }">{{ percent(row.shareRate) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="drillCategoryRow(row as GoodsRankRow)">下钻</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 按时段毛利 -->
    <el-card v-if="view === 'by-day'">
      <template #header><span>时段毛利分析（按营业日）</span></template>
      <el-table v-loading="loading" :data="dayData?.rows ?? []" stripe>
        <el-table-column prop="bizDate" label="日期" width="110" />
        <el-table-column label="单数" prop="orderCount" width="70" align="right" />
        <el-table-column label="件数" width="70" align="right">
          <template #default="{ row }">{{ row.itemCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.salesAmount) }}</template>
        </el-table-column>
        <el-table-column label="净销售额" width="100" align="right">
          <template #default="{ row }">{{ money(row.netSalesAmount) }}</template>
        </el-table-column>
        <el-table-column label="退货额" width="90" align="right">
          <template #default="{ row }">{{ money(row.refundAmount) }}</template>
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
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="drillDayRow(row as SalesReportRow)">下钻</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="dayData?.total" class="total-row">
        <span class="total-label">合计</span>
        <span>单数: {{ dayData.total.orderCount }}</span>
        <span>净销售: ￥{{ money(dayData.total.netAmount) }}</span>
        <span>毛利额: ￥{{ money(dayData.total.profitAmount) }}</span>
        <span>毛利率: {{ percent(dayData.total.profitRate) }}</span>
        <el-button link type="primary" @click="drillTotalRow">下钻</el-button>
      </div>
    </el-card>

    <!-- 负毛利清单 -->
    <el-card v-if="view === 'negative'">
      <template #header>
        <span>负毛利商品清单</span>
        <el-tag type="danger" size="small" class="header-tag">成交价低于成本价</el-tag>
      </template>
      <el-table v-loading="loading" :data="negativeRows" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="goodsCode" label="商品编码" width="120" />
        <el-table-column prop="goodsName" label="商品名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="categoryName" label="分类" width="120" show-overflow-tooltip />
        <el-table-column label="成交单价" width="90" align="right">
          <template #default="{ row }">{{ money(row.unitPrice) }}</template>
        </el-table-column>
        <el-table-column label="成本单价" width="90" align="right">
          <template #default="{ row }">{{ money(row.costPrice) }}</template>
        </el-table-column>
        <el-table-column label="单位毛利" width="90" align="right">
          <template #default="{ row }">
            <span class="loss">{{ money(row.unitProfit) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="销量" width="80" align="right">
          <template #default="{ row }">{{ qty(row.qty) }}</template>
        </el-table-column>
        <el-table-column label="单数" prop="orderCount" width="70" align="right" />
        <el-table-column label="实付金额" width="100" align="right">
          <template #default="{ row }">{{ money(row.paidAmount) }}</template>
        </el-table-column>
        <el-table-column label="销售成本" width="100" align="right">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </el-table-column>
        <el-table-column label="毛利额" width="100" align="right">
          <template #default="{ row }">
            <span class="loss">{{ money(row.profitAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="drillGoodsRow(row as NegativeProfitRow)">下钻</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

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
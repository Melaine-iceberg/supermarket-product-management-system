<script setup lang="ts">
import { moneyText } from '@/utils/format'
import { onMounted, reactive, ref, shallowRef } from 'vue'
import * as echarts from 'echarts'
import { useAuthStore } from '@/stores/auth'
import { pMoney, pPercent, printSheet, pTime, type PrintColumn } from '@/utils/print'
import {
  exportMemberReport,
  fetchBirthdayMembers,
  fetchDrillOrders,
  fetchMemberConsumptionRanking,
  fetchMemberContribution,
  fetchMemberGrowth,
  fetchMemberLevelDistribution,
  fetchSleepingMembers,
  todayRange,
  type DrillOrderFilters,
  type MemberContributionVO,
  type MemberRptRow,
  type ReportRange,
} from '@/api/report'
import DrillOrderDrawer from '../components/DrillOrderDrawer.vue'
import { RANGE_PRESETS, presetRange, type RangePreset } from '../components/rangePresets'

type ViewKey = 'growth' | 'consumption-ranking' | 'level-distribution' | 'contribution' | 'sleeping' | 'birthday'

const VIEW_LABELS: Record<ViewKey, string> = {
  growth: '增长趋势',
  'consumption-ranking': '消费排行',
  'level-distribution': '等级分布',
  contribution: '消费贡献',
  sleeping: '沉睡会员',
  birthday: '生日提醒',
}

const loading = ref(false)
const exporting = ref(false)
const auth = useAuthStore()
const view = ref<ViewKey>('growth')
const initRange = todayRange()
const range = reactive<ReportRange>({ ...initRange })
/** 日期选择器模型（el-date-picker 需要数组），查询前同步进 range */
const dateRange = ref<string[]>([initRange.beginDate, initRange.endDate])
const topN = ref(10)
const sleepingDays = ref(90)
const birthdayScope = ref<'month' | 'week'>('week')

// 数据容器
const growthRows = ref<MemberRptRow[]>([])
const rankingRows = ref<MemberRptRow[]>([])
const levelRows = ref<MemberRptRow[]>([])
const contribution = ref<MemberContributionVO | null>(null)
const sleepingRows = ref<MemberRptRow[]>([])
const sleepingTotal = ref(0)
const birthdayRows = ref<MemberRptRow[]>([])

const page = ref(1)
const size = ref(20)

const growthChartRef = ref<HTMLDivElement>()
const growthChart = shallowRef<echarts.ECharts>()
const levelChartRef = ref<HTMLDivElement>()
const levelChart = shallowRef<echarts.ECharts>()

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
}

function percent(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2) + '%'
}

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function fmtDate(v: string | null | undefined) {
  return v ? v.slice(0, 10) : '—'
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
    if (view.value === 'growth') {
      growthRows.value = await fetchMemberGrowth(range)
      renderGrowth()
    } else if (view.value === 'consumption-ranking') {
      rankingRows.value = await fetchMemberConsumptionRanking(topN.value)
    } else if (view.value === 'level-distribution') {
      levelRows.value = await fetchMemberLevelDistribution()
      renderLevel()
    } else if (view.value === 'contribution') {
      contribution.value = await fetchMemberContribution(range)
    } else if (view.value === 'sleeping') {
      const data = await fetchSleepingMembers(sleepingDays.value, page.value, size.value)
      sleepingRows.value = data.list
      sleepingTotal.value = data.total
    } else {
      birthdayRows.value = await fetchBirthdayMembers(birthdayScope.value)
    }
  } finally {
    loading.value = false
  }
}

function renderGrowth() {
  if (!growthChartRef.value || view.value !== 'growth') return
  if (!growthChart.value) {
    growthChart.value = echarts.init(growthChartRef.value)
  }
  growthChart.value.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['当日新增', '累计会员'] },
    grid: { left: 50, right: 50, top: 40, bottom: 30 },
    xAxis: {
      type: 'category',
      data: growthRows.value.map((r) => r.bizDate?.slice(5) ?? ''),
      axisLabel: { fontSize: 11 },
    },
    yAxis: [
      { type: 'value', name: '新增', axisLabel: { fontSize: 11 } },
      { type: 'value', name: '累计', axisLabel: { fontSize: 11 } },
    ],
    series: [
      {
        name: '当日新增',
        type: 'bar',
        data: growthRows.value.map((r) => r.newCount ?? 0),
        itemStyle: { color: '#409eff' },
        barMaxWidth: 20,
      },
      {
        name: '累计会员',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        data: growthRows.value.map((r) => r.cumulativeCount ?? 0),
        itemStyle: { color: '#67c23a' },
      },
    ],
  })
}

function renderLevel() {
  if (!levelChartRef.value || view.value !== 'level-distribution') return
  if (!levelChart.value) {
    levelChart.value = echarts.init(levelChartRef.value)
  }
  levelChart.value.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { orient: 'vertical', left: 10, top: 20 },
    series: [
      {
        name: '会员等级',
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: false,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { show: true, formatter: '{b}: {c}' },
        data: levelRows.value.map((r) => ({
          name: r.levelName ?? '未知',
          value: r.memberCount ?? 0,
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
    await exportMemberReport(view.value, range, {
      topN: topN.value,
      days: sleepingDays.value,
      scope: birthdayScope.value,
    })
    ElMessage.success('导出成功')
  } finally {
    exporting.value = false
  }
}

// ---------- 打印视图（FR-RPT-09）：列与后端 Excel 导出口径一致 ----------
/** 手机号已由服务端按角色脱敏（BR-MEM-08），打印件同样只出脱敏值 */
const CONSUME_COLUMNS: PrintColumn<MemberRptRow>[] = [
  { label: '会员编号', value: (r) => r.memberNo },
  { label: '姓名', value: (r) => r.name },
  { label: '手机号', value: (r) => r.phone },
  { label: '等级', value: (r) => r.levelName },
  { label: '积分', value: (r) => r.points, align: 'right' },
  { label: '累计消费', value: (r) => pMoney(r.totalConsume), align: 'right' },
  { label: '消费次数', value: (r) => r.consumeTimes, align: 'right' },
  { label: '最近消费时间', value: (r) => pTime(r.lastConsumeTime) },
]

async function handlePrint() {
  syncRange()
  const title = `会员报表 · ${VIEW_LABELS[view.value]}`
  const ranged = view.value === 'growth' || view.value === 'contribution'
  const meta = [
    ranged ? `统计区间：${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}` : null,
    `操作员：${auth.user?.realName ?? '—'}`,
  ]
  if (view.value === 'growth') {
    await printSheet({
      title,
      meta,
      columns: [
        { label: '日期', value: (r) => r.bizDate },
        { label: '当日新增', value: (r) => r.newCount, align: 'right' },
        { label: '累计会员数', value: (r) => r.cumulativeCount, align: 'right' },
      ],
      rows: growthRows.value,
    })
  } else if (view.value === 'consumption-ranking') {
    await printSheet({
      title,
      meta: [...meta, `前 ${topN.value} 名`],
      columns: CONSUME_COLUMNS,
      rows: rankingRows.value,
    })
  } else if (view.value === 'level-distribution') {
    await printSheet({
      title,
      meta,
      columns: [
        { label: '等级', value: (r) => r.levelName },
        { label: '会员数', value: (r) => r.memberCount, align: 'right' },
      ],
      rows: levelRows.value,
    })
  } else if (view.value === 'contribution') {
    const c = contribution.value
    const kv = c
      ? [
          { label: '会员销售额（净）', value: `￥${pMoney(c.memberNetAmount)}` },
          { label: '总销售额（净）', value: `￥${pMoney(c.totalNetAmount)}` },
          { label: '会员单数', value: String(c.memberOrderCount) },
          { label: '总单数', value: String(c.totalOrderCount) },
          { label: '会员贡献占比', value: pPercent(c.shareRate) },
        ]
      : []
    await printSheet({
      title,
      meta,
      columns: [
        { label: '指标', value: (r) => r.label },
        { label: '数值', value: (r) => r.value, align: 'right' },
      ],
      rows: kv,
    })
  } else if (view.value === 'sleeping') {
    await printSheet({
      title,
      meta: [...meta, `沉睡判定：${sleepingDays.value} 天未消费`, `第 ${page.value} 页 / 共 ${sleepingTotal.value} 条`],
      columns: [
        ...CONSUME_COLUMNS.filter((c) => c.label !== '积分'),
        { label: '注册时间', value: (r) => pTime(r.registerTime) },
        { label: '沉睡天数', value: (r) => r.sleepingDays, align: 'right' },
      ],
      rows: sleepingRows.value,
      orientation: 'landscape',
    })
  } else {
    await printSheet({
      title,
      meta: [...meta, birthdayScope.value === 'week' ? '范围：未来 7 天' : '范围：当月'],
      columns: [
        { label: '会员编号', value: (r) => r.memberNo },
        { label: '姓名', value: (r) => r.name },
        { label: '手机号', value: (r) => r.phone },
        { label: '等级', value: (r) => r.levelName },
        { label: '生日', value: (r) => (r.birthday ? r.birthday.slice(0, 10) : '—') },
      ],
      rows: birthdayRows.value,
    })
  }
}

// ---------- 下钻（BR-RPT-06）：汇总数字 → 构成它的销售单 ----------
const drillVisible = ref(false)
const drillTitle = ref('')
let drillFilters: DrillOrderFilters = {}

function openDrill(filters: DrillOrderFilters, label: string) {
  drillFilters = filters
  drillTitle.value = `下钻：${label}`
  drillVisible.value = true
}

function fetchDrillPage(p: number, size: number) {
  return fetchDrillOrders('member', { ...drillFilters, page: p, size })
}

/** 消费排行行 → 该会员的全部销售单（与“累计消费”同口径，不限日期） */
function drillRankingRow(row: MemberRptRow) {
  if (row.id == null) {
    ElMessage.warning('该会员暂无可下钻单据')
    return
  }
  openDrill({ memberId: row.id }, `会员 ${row.name ?? row.memberNo} 的销售单`)
}

/** 消费贡献 → 会员口径传 memberOnly=true；总额口径不传（null 表示不过滤，false 反而排除会员单） */
function drillContribution(memberOnly: boolean, label: string) {
  syncRange()
  openDrill(
    memberOnly ? { memberOnly: true, ...range } : { ...range },
    `${label}（${range.beginDate ?? '—'} ~ ${range.endDate ?? '—'}）`,
  )
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
        <el-form-item v-if="view === 'consumption-ranking'" label="取前 N 名">
          <el-input-number v-model="topN" :min="5" :max="100" :step="5" style="width: 100px" @change="load" />
        </el-form-item>
        <el-form-item v-if="view === 'sleeping'" label="沉睡天数">
          <el-input-number v-model="sleepingDays" :min="30" :max="365" :step="30" style="width: 110px" @change="load" />
        </el-form-item>
        <el-form-item v-if="view === 'birthday'" label="范围">
          <el-radio-group v-model="birthdayScope" @change="load">
            <el-radio-button value="week">未来7天</el-radio-button>
            <el-radio-button value="month">当月</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="view === 'growth' || view === 'contribution'" label="日期区间">
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
      <div v-if="view === 'growth' || view === 'contribution'" class="presets">
        <el-button v-for="p in RANGE_PRESETS" :key="p.key" size="small" @click="setRange(p.key)">
          {{ p.label }}
        </el-button>
      </div>
    </el-card>

    <!-- 增长趋势 -->
    <el-card v-if="view === 'growth'">
      <template #header><span>会员增长趋势</span></template>
      <div ref="growthChartRef" class="chart" />
      <el-table v-loading="loading" border :data="growthRows" stripe class="mt-16" max-height="300">
        <el-table-column prop="bizDate" label="日期" width="110">
          <template #default="{ row }">{{ fmtDate(row.bizDate) }}</template>
        </el-table-column>
        <el-table-column label="当日新增" prop="newCount" width="100" align="right" />
        <el-table-column label="累计会员" prop="cumulativeCount" width="100" align="right" />
      </el-table>
    </el-card>

    <!-- 消费排行 -->
    <el-card v-if="view === 'consumption-ranking'">
      <template #header><span>会员消费排行 TOP {{ topN }}</span></template>
      <el-table v-loading="loading" border :data="rankingRows" stripe>
        <el-table-column type="index" label="排名" width="60" align="center" />
        <el-table-column prop="memberNo" label="会员编号" width="120" />
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="levelName" label="等级" width="100" />
        <el-table-column label="积分" prop="points" width="80" align="right" />
        <el-table-column label="累计消费" width="110" align="right">
          <template #default="{ row }">
            <b>{{ money(row.totalConsume) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="消费次数" prop="consumeTimes" width="90" align="right" />
        <el-table-column label="最近消费" width="160">
          <template #default="{ row }">{{ fmtTime(row.lastConsumeTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="drillRankingRow(row as MemberRptRow)">消费明细</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 等级分布 -->
    <el-card v-if="view === 'level-distribution'">
      <template #header><span>会员等级分布</span></template>
      <div ref="levelChartRef" class="chart" />
      <el-table v-loading="loading" border :data="levelRows" stripe class="mt-16">
        <el-table-column prop="levelName" label="等级" min-width="160" />
        <el-table-column label="会员数" prop="memberCount" width="120" align="right" />
      </el-table>
    </el-card>

    <!-- 消费贡献 -->
    <el-card v-if="view === 'contribution'">
      <template #header><span>会员消费贡献占比</span></template>
      <div v-if="contribution" class="contribution">
        <el-row :gutter="16">
          <el-col :span="6">
            <div class="stat-card">
              <div class="stat-label">会员销售额</div>
              <div class="stat-value primary">￥{{ money(contribution.memberNetAmount) }}</div>
              <el-link type="primary" :underline="false" @click="drillContribution(true, '会员销售额')">查看会员单</el-link>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card">
              <div class="stat-label">总销售额</div>
              <div class="stat-value">￥{{ money(contribution.totalNetAmount) }}</div>
              <el-link type="primary" :underline="false" @click="drillContribution(false, '总销售额')">查看全部单据</el-link>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card">
              <div class="stat-label">会员单数</div>
              <div class="stat-value">{{ contribution.memberOrderCount }}</div>
              <el-link type="primary" :underline="false" @click="drillContribution(true, '会员单')">查看会员单</el-link>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card">
              <div class="stat-label">会员贡献占比</div>
              <div class="stat-value success">{{ percent(contribution.shareRate) }}</div>
            </div>
          </el-col>
        </el-row>
      </div>
    </el-card>

    <!-- 沉睡会员 -->
    <el-card v-if="view === 'sleeping'">
      <template #header><span>沉睡会员清单（{{ sleepingDays }} 天未消费）</span></template>
      <el-table v-loading="loading" border :data="sleepingRows" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="memberNo" label="会员编号" width="120" />
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="levelName" label="等级" width="100" />
        <el-table-column label="累计消费" width="110" align="right">
          <template #default="{ row }">{{ money(row.totalConsume) }}</template>
        </el-table-column>
        <el-table-column label="消费次数" prop="consumeTimes" width="90" align="right" />
        <el-table-column label="最近消费" width="160">
          <template #default="{ row }">{{ fmtTime(row.lastConsumeTime) }}</template>
        </el-table-column>
        <el-table-column label="注册时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.registerTime) }}</template>
        </el-table-column>
        <el-table-column label="沉睡天数" width="90" align="right">
          <template #default="{ row }">
            <el-tag type="warning" size="small">{{ row.sleepingDays }} 天</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="sleepingTotal"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- 生日提醒 -->
    <el-card v-if="view === 'birthday'">
      <template #header>
        <span>会员生日提醒（{{ birthdayScope === 'week' ? '未来7天' : '当月' }}）</span>
      </template>
      <el-table v-loading="loading" border :data="birthdayRows" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="memberNo" label="会员编号" width="120" />
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column prop="phone" label="手机号" width="140" />
        <el-table-column prop="levelName" label="等级" width="100" />
        <el-table-column label="生日" width="120">
          <template #default="{ row }">{{ fmtDate(row.birthday) }}</template>
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

  .chart {
    height: 300px;
  }

  .mt-16 {
    margin-top: 16px;
  }

  .pager {
    margin-top: 16px;
    justify-content: flex-end;
  }

  .contribution {
    .stat-card {
      text-align: center;
      padding: 20px;
      background: #f5f7fa;
      border-radius: 8px;

      .stat-label {
        font-size: 13px;
        color: #909399;
        margin-bottom: 8px;
      }

      .stat-value {
        font-size: 24px;
        font-weight: 700;
        color: #303133;

        &.primary {
          color: #409eff;
        }

        &.success {
          color: #67c23a;
        }
      }
    }
  }
}
</style>
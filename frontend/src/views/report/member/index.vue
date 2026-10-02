<script setup lang="ts">
import { onMounted, reactive, ref, shallowRef } from 'vue'
import * as echarts from 'echarts'
import {
  exportMemberReport,
  fetchBirthdayMembers,
  fetchMemberConsumptionRanking,
  fetchMemberContribution,
  fetchMemberGrowth,
  fetchMemberLevelDistribution,
  fetchSleepingMembers,
  recentDaysRange,
  thisMonthRange,
  type MemberContributionVO,
  type MemberRptRow,
  type ReportRange,
} from '@/api/report'

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
const view = ref<ViewKey>('growth')
const initRange = thisMonthRange()
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
  return v == null ? '—' : Number(v).toFixed(2)
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

function setRange(preset: 'month' | 'recent7' | 'recent30') {
  const r = preset === 'month' ? thisMonthRange() : recentDaysRange(preset === 'recent7' ? 7 : 30)
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
        <el-form-item v-if="view === 'consumption-ranking'" label="TOP N">
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
        </el-form-item>
      </el-form>
      <div v-if="view === 'growth' || view === 'contribution'" class="presets">
        <el-button size="small" @click="setRange('month')">本月</el-button>
        <el-button size="small" @click="setRange('recent7')">近7日</el-button>
        <el-button size="small" @click="setRange('recent30')">近30日</el-button>
      </div>
    </el-card>

    <!-- 增长趋势 -->
    <el-card v-if="view === 'growth'">
      <template #header><span>会员增长趋势</span></template>
      <div ref="growthChartRef" class="chart" />
      <el-table v-loading="loading" :data="growthRows" stripe class="mt-16" max-height="300">
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
      <el-table v-loading="loading" :data="rankingRows" stripe>
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
      </el-table>
    </el-card>

    <!-- 等级分布 -->
    <el-card v-if="view === 'level-distribution'">
      <template #header><span>会员等级分布</span></template>
      <div ref="levelChartRef" class="chart" />
      <el-table v-loading="loading" :data="levelRows" stripe class="mt-16">
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
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card">
              <div class="stat-label">总销售额</div>
              <div class="stat-value">￥{{ money(contribution.totalNetAmount) }}</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card">
              <div class="stat-label">会员单数</div>
              <div class="stat-value">{{ contribution.memberOrderCount }}</div>
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
      <el-table v-loading="loading" :data="sleepingRows" stripe>
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
      <el-table v-loading="loading" :data="birthdayRows" stripe>
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
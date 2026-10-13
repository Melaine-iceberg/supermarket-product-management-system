<script setup lang="ts">
import { moneyText } from '@/utils/format'
import { onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { fetchDashboard, todayRange, type DashboardVO } from '@/api/report'
import { useAuthStore } from '@/stores/auth'
import { pQty, printSheet, pTable } from '@/utils/print'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const data = ref<DashboardVO | null>(null)
const trendDays = ref<7 | 30>(7)

const chartRef = ref<HTMLDivElement>()
const chart = shallowRef<echarts.ECharts>()

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
}

function percent(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2) + '%'
}

async function load() {
  loading.value = true
  try {
    data.value = await fetchDashboard(trendDays.value)
    renderChart()
  } finally {
    loading.value = false
  }
}

function renderChart() {
  if (!chartRef.value || !data.value) return
  if (!chart.value) {
    chart.value = echarts.init(chartRef.value)
  }
  const trend = data.value.trend
  chart.value.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['净销售额', '订单数'] },
    grid: { left: 50, right: 50, top: 40, bottom: 30 },
    xAxis: {
      type: 'category',
      data: trend.map((p) => p.bizDate.slice(5)),
      axisLabel: { fontSize: 11 },
    },
    yAxis: [
      { type: 'value', name: '金额(元)', axisLabel: { fontSize: 11 } },
      { type: 'value', name: '单数', axisLabel: { fontSize: 11 } },
    ],
    series: [
      {
        name: '净销售额',
        type: 'line',
        smooth: true,
        data: trend.map((p) => Number(p.netSalesAmount)),
        itemStyle: { color: '#409eff' },
        areaStyle: { color: 'rgba(64,158,255,0.15)' },
      },
      {
        name: '订单数',
        type: 'bar',
        yAxisIndex: 1,
        data: trend.map((p) => p.orderCount),
        itemStyle: { color: '#67c23a' },
        barMaxWidth: 20,
      },
    ],
  })
}

function handleResize() {
  chart.value?.resize()
}

function goTodo(route: string) {
  router.push(route)
}

/** 看板打印（FR-RPT-09，A4 纵向）：KPI + 待办 + 趋势 + TOP10 分段落纸，图表以数据表代替 */
async function handlePrint() {
  const d = data.value
  if (!d) return
  const kpi = pTable(
    ['指标', '数值'],
    [
      ['今日销售额', `￥${money(d.kpi.salesAmount)}`],
      ['今日订单数', d.kpi.orderCount],
      ['今日毛利额', d.kpi.profitAmount == null ? '—' : `￥${money(d.kpi.profitAmount)}`],
      ['今日毛利率', percent(d.kpi.profitRate)],
      ['今日新增会员', d.kpi.newMemberCount],
      ['库存预警商品数', d.kpi.warningCount],
    ],
    ['left', 'right'],
  )
  const todos = pTable(
    ['待办事项', '数量'],
    d.todos.map((t) => [t.title, t.count]),
    ['left', 'right'],
  )
  const trend = pTable(
    ['营业日', '订单数', '净销售额'],
    d.trend.map((p) => [p.bizDate, p.orderCount, `￥${money(p.netSalesAmount)}`]),
    ['left', 'right', 'right'],
  )
  const top = pTable(
    ['排名', '商品名称', '销量', '销售额'],
    d.topGoods.map((g, i) => [i + 1, g.goodsName, pQty(g.qty), `￥${money(g.salesAmount)}`]),
    ['center', 'left', 'right', 'right'],
  )
  await printSheet({
    title: '经营概览看板',
    meta: [`统计日期：${todayRange().beginDate}`, `趋势区间：近 ${trendDays.value} 日`, `操作员：${auth.user?.realName ?? '—'}`],
    bodyHtml: [
      '<div class="sec">一、今日关键指标</div>',
      kpi,
      '<div class="sec">二、待办事项</div>',
      d.todos.length ? todos : '<div>无待办</div>',
      `<div class="sec">三、近 ${trendDays.value} 日销售趋势</div>`,
      trend,
      '<div class="sec">四、TOP10 热销商品</div>',
      top,
    ].join(''),
  })
}

onMounted(() => {
  load()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  chart.value?.dispose()
})

watch(trendDays, load)
</script>

<template>
  <div v-loading="loading" class="dashboard">
    <div class="toolbar">
      <el-button size="small" @click="handlePrint">打印看板</el-button>
    </div>

    <!-- KPI 卡片（FR-RPT-01） -->
    <el-row :gutter="16" class="kpi-row">
      <el-col :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-label">今日销售额</div>
          <div class="kpi-value primary">￥{{ money(data?.kpi.salesAmount) }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-label">今日订单数</div>
          <div class="kpi-value">{{ data?.kpi.orderCount ?? 0 }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-label">今日毛利额</div>
          <div class="kpi-value" :class="Number(data?.kpi.profitAmount ?? 0) >= 0 ? 'success' : 'danger'">
            ￥{{ money(data?.kpi.profitAmount) }}
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-label">毛利率</div>
          <div class="kpi-value">{{ percent(data?.kpi.profitRate) }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-label">今日新增会员</div>
          <div class="kpi-value">{{ data?.kpi.newMemberCount ?? 0 }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="kpi-card clickable" @click="router.push('/inventory/warning')">
          <div class="kpi-label">库存预警</div>
          <div class="kpi-value warning">{{ data?.kpi.warningCount ?? 0 }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <!-- 待办事项（FR-RPT-01：按角色权限过滤） -->
      <el-col :xs="24" :md="8">
        <el-card class="todo-card">
          <template #header>
            <span>待办事项</span>
          </template>
          <div v-if="data?.todos.length" class="todo-list">
            <div
              v-for="todo in data.todos"
              :key="todo.type"
              class="todo-item"
              @click="goTodo(todo.route)"
            >
              <span class="todo-title">{{ todo.title }}</span>
              <el-badge :value="todo.count" :max="999" type="danger" />
            </div>
          </div>
          <el-empty v-else description="暂无待办" :image-size="60" />
        </el-card>
      </el-col>

      <!-- 销售趋势（FR-RPT-01：近 7/30 日） -->
      <el-col :xs="24" :md="16">
        <el-card class="trend-card">
          <template #header>
            <div class="card-header">
              <span>销售趋势</span>
              <el-radio-group v-model="trendDays" size="small">
                <el-radio-button :value="7">近 7 日</el-radio-button>
                <el-radio-button :value="30">近 30 日</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <div ref="chartRef" class="chart" />
        </el-card>
      </el-col>
    </el-row>

    <!-- TOP10 热销商品（FR-RPT-01） -->
    <el-card class="top-card">
      <template #header>
        <span>TOP10 热销商品（近 {{ trendDays }} 日）</span>
      </template>
      <el-table :data="data?.topGoods ?? []" size="small" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="goodsName" label="商品名称" min-width="200" show-overflow-tooltip />
        <el-table-column label="销量" width="100" align="right">
          <template #default="{ row }">{{ Number(row.qty).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="销售额" width="120" align="right">
          <template #default="{ row }">￥{{ money(row.salesAmount) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.dashboard {
  .toolbar {
    display: flex;
    justify-content: flex-end;
    margin-bottom: 12px;
  }

  .kpi-row {
    margin-bottom: 16px;
  }

  .kpi-card {
    text-align: center;
    margin-bottom: 12px;

    &.clickable {
      cursor: pointer;
      transition: all 0.2s;

      &:hover {
        transform: translateY(-2px);
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
      }
    }

    .kpi-label {
      font-size: 13px;
      color: #909399;
      margin-bottom: 8px;
    }

    .kpi-value {
      font-size: 22px;
      font-weight: 700;
      color: #303133;

      &.primary {
        color: #409eff;
      }

      &.success {
        color: #67c23a;
      }

      &.danger {
        color: #f56c6c;
      }

      &.warning {
        color: #e6a23c;
      }
    }
  }

  .todo-card,
  .trend-card,
  .top-card {
    margin-bottom: 16px;
  }

  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .todo-list {
    .todo-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 8px;
      border-bottom: 1px solid #f0f0f0;
      cursor: pointer;
      transition: background 0.2s;

      &:last-child {
        border-bottom: none;
      }

      &:hover {
        background: #f5f7fa;
      }

      .todo-title {
        font-size: 14px;
        color: #606266;
      }
    }
  }

  .chart {
    height: 280px;
  }
}
</style>
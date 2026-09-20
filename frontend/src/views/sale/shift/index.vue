<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  closeShift,
  getCurrentShift,
  getShiftDetail,
  openShift,
  pageShifts,
  RETURN_STATUS_MAP,
  SHIFT_STATUS_MAP,
  type ShiftDetailVO,
  type ShiftVO,
} from '@/api/sale'

const auth = useAuthStore()

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '0.00' : Number(v).toFixed(2)
}

const canOpen = computed(() => auth.hasPerm('sys:shift:open'))
const canClose = computed(() => auth.hasPerm('sys:shift:close'))

// ---------- 当前班次（开班/交班入口） ----------
const currentShift = ref<ShiftVO | null>(null)
const acting = ref(false)

async function loadCurrent() {
  currentShift.value = await getCurrentShift()
}

async function doOpenShift() {
  const { value } = await ElMessageBox.prompt(
    '请输入收银台号（1~99）',
    '开班登记',
    {
      inputValue: currentShift.value ? String(currentShift.value.counterNo) : '1',
      inputPattern: /^([1-9]|[1-9]\d)$/,
      inputErrorMessage: '台号须为 1~99 的整数',
      type: 'info',
    },
  )
  acting.value = true
  try {
    currentShift.value = await openShift(Number(value))
    ElMessage.success(`已在 ${currentShift.value.counterNo} 号台开班`)
    load()
  } catch {
    // 拦截器已提示
  } finally {
    acting.value = false
  }
}

async function doCloseShift() {
  await ElMessageBox.confirm(
    '交班后将统计本班次销售与退款生成交班单，本班次单据的退货须店长处理（BR-SALE-09）。确认交班？',
    '交班对账',
    { type: 'warning' },
  )
  acting.value = true
  try {
    const detail = await closeShift()
    currentShift.value = null
    resultDialog.detail = detail
    resultDialog.visible = true
    load()
  } catch {
    // 拦截器已提示
  } finally {
    acting.value = false
  }
}

// ---------- 查询 ----------
const loading = ref(false)
const list = ref<ShiftVO[]>([])
const total = ref(0)
const query = reactive({
  cashierName: '',
  counterNo: null as number | null,
  status: '',
  dateRange: [] as string[],
  page: 1,
  size: 20,
})

async function load() {
  loading.value = true
  try {
    const data = await pageShifts({
      cashierName: query.cashierName.trim() || undefined,
      counterNo: query.counterNo ?? undefined,
      status: query.status || undefined,
      beginDate: query.dateRange?.[0],
      endDate: query.dateRange?.[1],
      page: query.page,
      size: query.size,
    })
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

function resetQuery() {
  Object.assign(query, { cashierName: '', counterNo: null, status: '', dateRange: [], page: 1 })
  load()
}

onMounted(() => {
  load()
  if (canOpen.value || canClose.value) loadCurrent()
})

// ---------- 班次详情 / 交班单（FR-RPT-09 打印视图） ----------
const drawer = reactive({ visible: false, loading: false, detail: null as ShiftDetailVO | null })
const resultDialog = reactive({ visible: false, detail: null as ShiftDetailVO | null })

async function openDetail(row: ShiftVO) {
  drawer.visible = true
  drawer.loading = true
  try {
    drawer.detail = await getShiftDetail(row.id)
  } finally {
    drawer.loading = false
  }
}

/** 打印交班单（FR-RPT-09）：弹出打印视图 */
function printShift(detail: ShiftDetailVO) {
  const s = detail.shift
  const rows = detail.orders
    .map(
      (o) => `<tr>
        <td>${o.orderNo}</td>
        <td class="r">${o.itemCount}</td>
        <td class="r">${money(o.payableAmount)}</td>
        <td class="r">${money(o.paidAmount)}</td>
        <td class="r">${money(o.changeAmount)}</td>
        <td class="r">${Number(o.refundAmount) > 0 ? money(o.refundAmount) : '—'}</td>
      </tr>`,
    )
    .join('')
  const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>交班单 ${s.id}</title>
<style>
  body { font-family: "Microsoft YaHei", sans-serif; padding: 24px; color: #303133; }
  h2 { text-align: center; margin-bottom: 4px; }
  .meta { text-align: center; color: #909399; font-size: 12px; margin-bottom: 16px; }
  .info { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 6px 20px; font-size: 13px; margin-bottom: 14px; }
  .stats { width: 100%; border-collapse: collapse; margin-bottom: 16px; }
  .stats td { border: 1px solid #dcdfe6; padding: 6px 12px; font-size: 13px; }
  .stats .num { text-align: right; font-variant-numeric: tabular-nums; }
  table.list { width: 100%; border-collapse: collapse; }
  table.list th, table.list td { border: 1px solid #dcdfe6; padding: 5px 10px; font-size: 12px; }
  table.list th { background: #f5f7fa; }
  .r { text-align: right; font-variant-numeric: tabular-nums; }
  .footer { margin-top: 18px; display: flex; justify-content: space-between; font-size: 13px; }
</style>
</head>
<body>
  <h2>交班单</h2>
  <div class="meta">单据编号：SHIFT-${String(s.id).padStart(6, '0')} ｜ 打印时间：${new Date().toLocaleString('zh-CN', { hour12: false })}</div>
  <div class="info">
    <span>收银员：${s.cashierName}</span>
    <span>收银台：${String(s.counterNo).padStart(2, '0')} 号</span>
    <span>状态：${s.status === 'CLOSED' ? '已交班' : '在班'}</span>
    <span>开班时间：${fmtTime(s.startTime)}</span>
    <span>交班时间：${fmtTime(s.endTime)}</span>
    <span>销售单数：${s.orderCount}</span>
  </div>
  <table class="stats">
    <tr>
      <td>现金收款</td><td class="num">￥${money(s.cashAmount)}</td>
      <td>银行卡收款</td><td class="num">￥${money(s.bankAmount)}</td>
    </tr>
    <tr>
      <td>移动支付收款</td><td class="num">￥${money(s.mobileAmount)}</td>
      <td>储值收款</td><td class="num">￥${money(s.balanceAmount)}</td>
    </tr>
    <tr>
      <td>退货额</td><td class="num">￥${money(s.returnAmount)}</td>
      <td>净销售额（Σ应收−退货）</td><td class="num">￥${money(s.salesAmount)}</td>
    </tr>
    <tr>
      <td colspan="3"><b>应收现金（现金收款 − 现金退款）</b></td>
      <td class="num"><b>￥${money(s.expectedCash)}</b></td>
    </tr>
  </table>
  <table class="list">
    <thead>
      <tr><th>销售单号</th><th>件数</th><th>应收</th><th>实收</th><th>找零</th><th>退款</th></tr>
    </thead>
    <tbody>${rows || '<tr><td colspan="6" style="text-align:center;color:#909399">本班次无销售单</td></tr>'}</tbody>
  </table>
  <div class="footer">
    <span>收银员签字：________________</span>
    <span>店长签字：________________</span>
  </div>
</body>
</html>`
  const win = window.open('', '_blank', 'width=820,height=700')
  if (!win) {
    ElMessage.warning('浏览器拦截了打印窗口，请允许弹出窗口')
    return
  }
  win.document.write(html)
  win.document.close()
  win.focus()
  win.print()
}
</script>

<template>
  <div class="page">
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="收银员">
          <el-input v-model="query.cashierName" placeholder="姓名" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="收银台">
          <el-input-number
            v-model="query.counterNo"
            :min="1"
            :max="99"
            :controls="false"
            placeholder="台号"
            style="width: 90px"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 100px">
            <el-option v-for="(v, k) in SHIFT_STATUS_MAP" :key="k" :label="v.label" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="开班日期">
          <el-date-picker
            v-model="query.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-head">
          <span>
            班次列表
            <el-tag v-if="currentShift" type="success" size="small" class="working-tag">
              当前在班：{{ currentShift.cashierName }} @ {{ String(currentShift.counterNo).padStart(2, '0') }} 号台（{{ fmtTime(currentShift.startTime) }} 开班）
            </el-tag>
          </span>
          <div>
            <el-button v-if="canOpen && !currentShift" type="primary" :loading="acting" @click="doOpenShift">
              开班
            </el-button>
            <el-button v-if="canClose && currentShift" type="warning" :loading="acting" @click="doCloseShift">
              交班
            </el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column label="班次" width="90" fixed="left">
          <template #default="{ row }">#{{ row.id }}</template>
        </el-table-column>
        <el-table-column prop="cashierName" label="收银员" width="90" />
        <el-table-column prop="counterNo" label="台号" width="60" align="center">
          <template #default="{ row }">{{ String(row.counterNo).padStart(2, '0') }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="SHIFT_STATUS_MAP[row.status]?.type ?? 'info'" size="small">
              {{ SHIFT_STATUS_MAP[row.status]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="orderCount" label="销售单数" width="85" align="right" />
        <el-table-column label="净销售额" width="100" align="right">
          <template #default="{ row }">
            <b>{{ money(row.salesAmount) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="现金" width="90" align="right">
          <template #default="{ row }">{{ money(row.cashAmount) }}</template>
        </el-table-column>
        <el-table-column label="银行卡" width="90" align="right">
          <template #default="{ row }">{{ money(row.bankAmount) }}</template>
        </el-table-column>
        <el-table-column label="移动支付" width="90" align="right">
          <template #default="{ row }">{{ money(row.mobileAmount) }}</template>
        </el-table-column>
        <el-table-column label="退货额" width="90" align="right">
          <template #default="{ row }">
            <span :class="{ refunded: Number(row.returnAmount) > 0 }">{{ money(row.returnAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="应收现金" width="100" align="right">
          <template #default="{ row }">
            <b class="payable">{{ money(row.expectedCash) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="开班时间" width="165">
          <template #default="{ row }">{{ fmtTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="交班时间" width="165">
          <template #default="{ row }">{{ fmtTime(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row as ShiftVO)">交班单</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @change="load"
      />
    </el-card>

    <!-- 班次详情（交班单） -->
    <el-drawer v-model="drawer.visible" title="交班单" size="56%">
      <div v-loading="drawer.loading">
        <template v-if="drawer.detail">
          <div class="detail-head">
            <div class="detail-actions">
              <el-button type="primary" @click="printShift(drawer.detail)">打印交班单</el-button>
            </div>
          </div>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="班次">#{{ drawer.detail.shift.id }}</el-descriptions-item>
            <el-descriptions-item label="收银员">{{ drawer.detail.shift.cashierName }}</el-descriptions-item>
            <el-descriptions-item label="收银台">{{ String(drawer.detail.shift.counterNo).padStart(2, '0') }} 号</el-descriptions-item>
            <el-descriptions-item label="开班时间">{{ fmtTime(drawer.detail.shift.startTime) }}</el-descriptions-item>
            <el-descriptions-item label="交班时间">{{ fmtTime(drawer.detail.shift.endTime) }}</el-descriptions-item>
            <el-descriptions-item label="销售单数">{{ drawer.detail.shift.orderCount }}</el-descriptions-item>
          </el-descriptions>

          <h4 class="section-title">对账统计</h4>
          <div class="stats-grid">
            <div class="stat-item">
              <span>现金收款</span><b>￥{{ money(drawer.detail.shift.cashAmount) }}</b>
            </div>
            <div class="stat-item">
              <span>银行卡收款</span><b>￥{{ money(drawer.detail.shift.bankAmount) }}</b>
            </div>
            <div class="stat-item">
              <span>移动支付收款</span><b>￥{{ money(drawer.detail.shift.mobileAmount) }}</b>
            </div>
            <div class="stat-item">
              <span>储值收款</span><b>￥{{ money(drawer.detail.shift.balanceAmount) }}</b>
            </div>
            <div class="stat-item">
              <span>退货额</span><b class="refunded">￥{{ money(drawer.detail.shift.returnAmount) }}</b>
            </div>
            <div class="stat-item">
              <span>净销售额</span><b>￥{{ money(drawer.detail.shift.salesAmount) }}</b>
            </div>
            <div class="stat-item highlight">
              <span>应收现金</span><b>￥{{ money(drawer.detail.shift.expectedCash) }}</b>
            </div>
          </div>

          <h4 class="section-title">班次内销售单（{{ drawer.detail.orders.length }} 单）</h4>
          <el-table :data="drawer.detail.orders" size="small" border max-height="380">
            <el-table-column prop="orderNo" label="销售单号" width="170" />
            <el-table-column prop="saleTime" label="销售时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.saleTime) }}</template>
            </el-table-column>
            <el-table-column prop="itemCount" label="件数" width="60" align="right" />
            <el-table-column label="应收" width="85" align="right">
              <template #default="{ row }">{{ money(row.payableAmount) }}</template>
            </el-table-column>
            <el-table-column label="实收" width="85" align="right">
              <template #default="{ row }">{{ money(row.paidAmount) }}</template>
            </el-table-column>
            <el-table-column label="找零" width="75" align="right">
              <template #default="{ row }">{{ money(row.changeAmount) }}</template>
            </el-table-column>
            <el-table-column label="退货状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="RETURN_STATUS_MAP[row.returnStatus]?.type ?? 'info'" size="small">
                  {{ RETURN_STATUS_MAP[row.returnStatus]?.label ?? row.returnStatus }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="退款" width="80" align="right">
              <template #default="{ row }">
                <span v-if="Number(row.refundAmount) > 0" class="refunded">{{ money(row.refundAmount) }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="muted note">注：交班后本班次单据的退货须店长处理（BR-SALE-09），退货额实时计入原班次。</div>
        </template>
      </div>
    </el-drawer>

    <!-- 交班结果（FR-SALE-12：交班单供核对） -->
    <el-dialog v-model="resultDialog.visible" title="交班完成" width="520px">
      <template v-if="resultDialog.detail">
        <div class="result-body">
          <div class="result-head">
            <el-icon color="#67c23a" :size="40"><CircleCheckFilled /></el-icon>
            <div class="result-title">交班单已生成</div>
            <div class="result-meta">
              {{ resultDialog.detail.shift.cashierName }} @ {{ String(resultDialog.detail.shift.counterNo).padStart(2, '0') }} 号台 ｜
              {{ resultDialog.detail.shift.orderCount }} 单
            </div>
          </div>
          <div class="stats-grid">
            <div class="stat-item">
              <span>现金收款</span><b>￥{{ money(resultDialog.detail.shift.cashAmount) }}</b>
            </div>
            <div class="stat-item">
              <span>银行卡</span><b>￥{{ money(resultDialog.detail.shift.bankAmount) }}</b>
            </div>
            <div class="stat-item">
              <span>移动支付</span><b>￥{{ money(resultDialog.detail.shift.mobileAmount) }}</b>
            </div>
            <div class="stat-item">
              <span>退货额</span><b class="refunded">￥{{ money(resultDialog.detail.shift.returnAmount) }}</b>
            </div>
            <div class="stat-item">
              <span>净销售额</span><b>￥{{ money(resultDialog.detail.shift.salesAmount) }}</b>
            </div>
            <div class="stat-item highlight">
              <span>应收现金</span><b>￥{{ money(resultDialog.detail.shift.expectedCash) }}</b>
            </div>
          </div>
        </div>
      </template>
      <template #footer>
        <el-button @click="resultDialog.visible = false">关闭</el-button>
        <el-button type="primary" @click="printShift(resultDialog.detail!)">打印交班单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.page {
  .search-card {
    margin-bottom: 12px;

    :deep(.el-card__body) {
      padding-bottom: 2px;
    }
  }

  .card-head {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .working-tag {
      margin-left: 10px;
    }
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }

  .muted {
    color: #909399;
  }

  .payable {
    color: #f56c6c;
  }

  .refunded {
    color: #e6a23c;
  }

  .detail-head {
    display: flex;
    justify-content: flex-end;
    margin-bottom: 10px;
  }

  .section-title {
    margin: 18px 0 8px;
    font-size: 14px;
    color: #303133;
  }

  .stats-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 8px;

    .stat-item {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 10px 14px;
      background: #f5f7fa;
      border-radius: 6px;

      span {
        font-size: 12px;
        color: #909399;
      }

      b {
        font-size: 16px;
        font-variant-numeric: tabular-nums;
      }

      &.highlight {
        background: #fdf6ec;

        b {
          color: #e6a23c;
        }
      }
    }
  }

  .note {
    margin-top: 8px;
    font-size: 12px;
  }

  .result-body {
    .result-head {
      text-align: center;
      margin-bottom: 16px;

      .result-title {
        margin-top: 4px;
        font-size: 16px;
        font-weight: 600;
      }

      .result-meta {
        margin-top: 4px;
        font-size: 13px;
        color: #909399;
      }
    }

    .stats-grid {
      grid-template-columns: repeat(2, 1fr);
    }
  }
}
</style>

<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm, prompt } from '@/utils/confirm'
import { moneyText } from '@/utils/format'
import { computed, onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  closeShift,
  getCurrentShift,
  getShiftDetail,
  listHolds,
  openShift,
  pageShifts,
  RETURN_STATUS_MAP,
  SHIFT_STATUS_MAP,
  type ShiftDetailVO,
  type ShiftVO,
} from '@/api/sale'
import { pMoney, pTable, pTime, printSheet } from '@/utils/print'

const auth = useAuthStore()

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '0.00' : moneyText(v, 2)
}

/** Element 标签类型 → Nuxt UI 徽标颜色 */
function tagColor(type?: 'info' | 'warning' | 'success' | 'danger') {
  if (type === 'success') return 'success' as const
  if (type === 'warning') return 'warning' as const
  if (type === 'danger') return 'error' as const
  return 'neutral' as const
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
  const { value } = await prompt(
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
    notify.success(`已在 ${currentShift.value.counterNo} 号台开班`)
    load()
  } catch {
    // 拦截器已提示
  } finally {
    acting.value = false
  }
}

async function doCloseShift() {
  // 未取挂单提示（FR-SALE-08）：挂单不扣库存不计报表，交班前提醒先处理
  let holds = 0
  try {
    holds = (await listHolds()).length
  } catch {
    // 查询失败不阻断交班
  }
  await confirm(
    holds > 0
      ? `您还有 ${holds} 张未取挂单（挂单不扣库存、不计入报表），建议先取单结算或删除。确认继续交班？`
      : '交班后将统计本班次销售与退款生成交班单，本班次单据的退货须店长处理（BR-SALE-09）。确认交班？',
    holds > 0 ? '存在未取挂单' : '交班对账',
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

/** 打印交班单（FR-RPT-09、FR-SALE-12）：A4 纵向，含班次汇总与本班次销售单清单 */
function printShift(detail: ShiftDetailVO) {
  const s = detail.shift
  const summary = pTable(
    ['项目', '数值'],
    [
      ['销售单数', s.orderCount],
      ['现金收款', `￥${pMoney(s.cashAmount)}`],
      ['银行卡收款', `￥${pMoney(s.bankAmount)}`],
      ['移动支付收款', `￥${pMoney(s.mobileAmount)}`],
      ['储值收款', `￥${pMoney(s.balanceAmount)}`],
      ['退货额', `￥${pMoney(s.returnAmount)}`],
      ['净销售额（Σ应收 − 退货）', `￥${pMoney(s.salesAmount)}`],
      ['应收现金（现金收款 − 现金退款）', `￥${pMoney(s.expectedCash)}`],
    ],
    ['left', 'right'],
  )
  const orders = detail.orders.length
    ? pTable(
        ['销售单号', '销售时间', '件数', '应收', '实收', '找零', '退款'],
        detail.orders.map((o) => [
          o.orderNo,
          pTime(o.saleTime),
          o.itemCount,
          pMoney(o.payableAmount),
          pMoney(o.paidAmount),
          pMoney(o.changeAmount),
          Number(o.refundAmount) > 0 ? pMoney(o.refundAmount) : '—',
        ]),
        ['left', 'left', 'right', 'right', 'right', 'right', 'right'],
      )
    : '<div class="c">本班次无销售单</div>'

  printSheet({
    title: '交班单',
    meta: [
      `单据编号：SHIFT-${String(s.id).padStart(6, '0')}`,
      `收银员：${s.cashierName}｜收银台：${String(s.counterNo).padStart(2, '0')} 号`,
      `状态：${SHIFT_STATUS_MAP[s.status]?.label ?? s.status}`,
      `开班时间：${pTime(s.startTime)}｜交班时间：${pTime(s.endTime)}`,
    ],
    bodyHtml: `<div class="sec">一、班次汇总</div>${summary}<div class="sec">二、本班次销售单</div>${orders}`,
    signs: ['收银员签字：____________', '店长签字：____________'],
  })
}
</script>

<template>
  <div class="page">
    <UCard class="search-card">
      <AppForm inline @submit.prevent>
        <AppFormItem label="收银员">
          <AppInput v-model="query.cashierName" placeholder="姓名" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="收银台">
          <AppInputNumber
            v-model="query.counterNo"
            :min="1"
            :max="99"
            :controls="false"
            placeholder="台号"
            style="width: 90px"
          />
        </AppFormItem>
        <AppFormItem label="状态">
          <AppSelect v-model="query.status" clearable placeholder="全部" style="width: 100px">
            <AppOption v-for="(v, k) in SHIFT_STATUS_MAP" :key="k" :label="v.label" :value="k" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="开班日期">
          <AppDatePicker
            v-model="query.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </AppFormItem>
        <AppFormItem>
          <UButton label="查询" @click="search" />
          <UButton color="neutral" variant="outline" label="重置" @click="resetQuery" />
        </AppFormItem>
      </AppForm>
    </UCard>

    <UCard>
      <template #header>
        <div class="card-head">
          <span>
            班次列表
            <UBadge v-if="currentShift" color="success" variant="soft" size="sm" class="working-tag">
              当前在班：{{ currentShift.cashierName }} @ {{ String(currentShift.counterNo).padStart(2, '0') }} 号台（{{ fmtTime(currentShift.startTime) }} 开班）
            </UBadge>
          </span>
          <div>
            <UButton v-if="canOpen && !currentShift" label="开班" :loading="acting" @click="doOpenShift" />
            <UButton
              v-if="canClose && currentShift"
              color="warning"
              label="交班"
              :loading="acting"
              @click="doCloseShift"
            />
          </div>
        </div>
      </template>

      <AppTable :loading="loading" border :data="list" stripe>
        <AppTableColumn label="班次" width="90" fixed="left">
          <template #default="{ row }">#{{ row.id }}</template>
        </AppTableColumn>
        <AppTableColumn prop="cashierName" label="收银员" width="90" />
        <AppTableColumn prop="counterNo" label="台号" width="60" align="center">
          <template #default="{ row }">{{ String(row.counterNo).padStart(2, '0') }}</template>
        </AppTableColumn>
        <AppTableColumn label="状态" width="80" align="center">
          <template #default="{ row }">
            <UBadge :color="tagColor(SHIFT_STATUS_MAP[row.status]?.type)" variant="soft" size="sm">
              {{ SHIFT_STATUS_MAP[row.status]?.label ?? row.status }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="orderCount" label="销售单数" width="85" align="right" />
        <AppTableColumn label="净销售额" width="100" align="right">
          <template #default="{ row }">
            <b>{{ money(row.salesAmount) }}</b>
          </template>
        </AppTableColumn>
        <AppTableColumn label="现金" width="90" align="right">
          <template #default="{ row }">{{ money(row.cashAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="银行卡" width="90" align="right">
          <template #default="{ row }">{{ money(row.bankAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="移动支付" width="90" align="right">
          <template #default="{ row }">{{ money(row.mobileAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="退货额" width="90" align="right">
          <template #default="{ row }">
            <span :class="{ refunded: Number(row.returnAmount) > 0 }">{{ money(row.returnAmount) }}</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="应收现金" width="100" align="right">
          <template #default="{ row }">
            <b class="payable">{{ money(row.expectedCash) }}</b>
          </template>
        </AppTableColumn>
        <AppTableColumn label="开班时间" width="165">
          <template #default="{ row }">{{ fmtTime(row.startTime) }}</template>
        </AppTableColumn>
        <AppTableColumn label="交班时间" width="165">
          <template #default="{ row }">{{ fmtTime(row.endTime) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton variant="link" size="sm" label="交班单" @click="openDetail(row as ShiftVO)" />
          </template>
        </AppTableColumn>
      </AppTable>

      <AppPagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @change="load"
      />
    </UCard>

    <!-- 班次详情（交班单） -->
    <AppDrawer v-model="drawer.visible" title="交班单" size="56%">
      <div v-loading="drawer.loading">
        <template v-if="drawer.detail">
          <div class="detail-head">
            <div class="detail-actions">
              <UButton label="打印交班单" @click="printShift(drawer.detail)" />
            </div>
          </div>
          <AppDescriptions :column="3" border size="small">
            <AppDescriptionsItem label="班次">#{{ drawer.detail.shift.id }}</AppDescriptionsItem>
            <AppDescriptionsItem label="收银员">{{ drawer.detail.shift.cashierName }}</AppDescriptionsItem>
            <AppDescriptionsItem label="收银台">{{ String(drawer.detail.shift.counterNo).padStart(2, '0') }} 号</AppDescriptionsItem>
            <AppDescriptionsItem label="开班时间">{{ fmtTime(drawer.detail.shift.startTime) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="交班时间">{{ fmtTime(drawer.detail.shift.endTime) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="销售单数">{{ drawer.detail.shift.orderCount }}</AppDescriptionsItem>
          </AppDescriptions>

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
          <AppTable :data="drawer.detail.orders" size="small" border max-height="380">
            <AppTableColumn prop="orderNo" label="销售单号" width="170" />
            <AppTableColumn prop="saleTime" label="销售时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.saleTime) }}</template>
            </AppTableColumn>
            <AppTableColumn prop="itemCount" label="件数" width="60" align="right" />
            <AppTableColumn label="应收" width="85" align="right">
              <template #default="{ row }">{{ money(row.payableAmount) }}</template>
            </AppTableColumn>
            <AppTableColumn label="实收" width="85" align="right">
              <template #default="{ row }">{{ money(row.paidAmount) }}</template>
            </AppTableColumn>
            <AppTableColumn label="找零" width="75" align="right">
              <template #default="{ row }">{{ money(row.changeAmount) }}</template>
            </AppTableColumn>
            <AppTableColumn label="退货状态" width="90" align="center">
              <template #default="{ row }">
                <UBadge :color="tagColor(RETURN_STATUS_MAP[row.returnStatus]?.type)" variant="soft" size="sm">
                  {{ RETURN_STATUS_MAP[row.returnStatus]?.label ?? row.returnStatus }}
                </UBadge>
              </template>
            </AppTableColumn>
            <AppTableColumn label="退款" width="80" align="right">
              <template #default="{ row }">
                <span v-if="Number(row.refundAmount) > 0" class="refunded">{{ money(row.refundAmount) }}</span>
                <span v-else class="muted">—</span>
              </template>
            </AppTableColumn>
          </AppTable>
          <div class="muted note">注：交班后本班次单据的退货须店长处理（BR-SALE-09），退货额实时计入原班次。</div>
        </template>
      </div>
    </AppDrawer>

    <!-- 交班结果（FR-SALE-12：交班单供核对） -->
    <AppModal v-model="resultDialog.visible" title="交班完成" width="520px">
      <template v-if="resultDialog.detail">
        <div class="result-body">
          <div class="result-head">
            <UIcon name="i-lucide-circle-check-big" class="size-10 text-success" />
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
        <UButton color="neutral" variant="outline" label="关闭" @click="resultDialog.visible = false" />
        <UButton label="打印交班单" @click="printShift(resultDialog.detail!)" />
      </template>
    </AppModal>
  </div>
</template>

<style scoped lang="scss">
.page {
  .search-card {
    margin-bottom: 12px;

    :deep([data-slot='body']) {
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
    color: var(--el-text-color-secondary);
  }

  .payable {
    color: var(--el-color-danger);
  }

  .refunded {
    color: var(--el-color-warning);
  }

  .detail-head {
    display: flex;
    justify-content: flex-end;
    margin-bottom: 10px;
  }

  .section-title {
    margin: 18px 0 8px;
    font-size: 14px;
    color: var(--el-text-color-primary);
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
      background: var(--el-bg-color-page);
      border-radius: 6px;

      span {
        font-size: 12px;
        color: var(--el-text-color-secondary);
      }

      b {
        font-size: 16px;
        font-variant-numeric: tabular-nums;
      }

      &.highlight {
        background: var(--el-color-warning-light-9);

        b {
          color: var(--el-color-warning);
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
        color: var(--el-text-color-secondary);
      }
    }

    .stats-grid {
      grid-template-columns: repeat(2, 1fr);
    }
  }
}
</style>

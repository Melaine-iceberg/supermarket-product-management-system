<script setup lang="ts">
import { moneyText } from '@/utils/format'
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  getSalesOrderDetail,
  pageSalesOrders,
  PAY_TYPE_MAP,
  RETURN_REASON_MAP,
  RETURN_STATUS_MAP,
  type SalesOrderDetailVO,
  type SalesOrderVO,
} from '@/api/sale'
import { printSalesReceipt } from '@/utils/receipt'

const route = useRoute()

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
}

/** Element 标签类型 → Nuxt UI 徽标颜色 */
function tagColor(type?: 'info' | 'warning' | 'success' | 'danger') {
  if (type === 'success') return 'success' as const
  if (type === 'warning') return 'warning' as const
  if (type === 'danger') return 'error' as const
  return 'neutral' as const
}

// ---------- 查询（FR-SALE-09） ----------
const loading = ref(false)
const list = ref<SalesOrderVO[]>([])
const total = ref(0)
const query = reactive({
  orderNo: '',
  cashierName: '',
  counterNo: null as number | null,
  memberKeyword: '',
  memberOnly: '' as '' | 'true' | 'false',
  payType: '',
  returnStatus: '',
  dateRange: [] as string[],
  amountMin: null as number | null,
  amountMax: null as number | null,
  page: 1,
  size: 20,
})

async function load() {
  loading.value = true
  try {
    const data = await pageSalesOrders({
      orderNo: query.orderNo.trim() || undefined,
      cashierName: query.cashierName.trim() || undefined,
      counterNo: query.counterNo ?? undefined,
      memberKeyword: query.memberKeyword.trim() || undefined,
      memberOnly: query.memberOnly === '' ? undefined : query.memberOnly === 'true',
      payType: query.payType || undefined,
      returnStatus: query.returnStatus || undefined,
      beginDate: query.dateRange?.[0],
      endDate: query.dateRange?.[1],
      amountMin: query.amountMin ?? undefined,
      amountMax: query.amountMax ?? undefined,
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
  Object.assign(query, {
    orderNo: '',
    cashierName: '',
    counterNo: null,
    memberKeyword: '',
    memberOnly: '',
    payType: '',
    returnStatus: '',
    dateRange: [],
    amountMin: null,
    amountMax: null,
    page: 1,
  })
  load()
}

// 报表下钻深链（BR-RPT-06）：?orderNo=XS... 自动筛选并打开该单据详情
async function bootstrap() {
  const no = route.query.orderNo
  if (typeof no === 'string' && no) {
    query.orderNo = no
    await load()
    list.value = list.value.filter((o) => o.orderNo === no)
    const target = list.value[0]
    if (target) openDetail(target)
  } else {
    await load()
  }
}

onMounted(bootstrap)

// ---------- 详情（FR-SALE-09：明细快照 + 收款 + 关联退货） ----------
const drawer = reactive({ visible: false, loading: false, detail: null as SalesOrderDetailVO | null })

async function openDetail(row: SalesOrderVO) {
  drawer.visible = true
  drawer.loading = true
  try {
    drawer.detail = await getSalesOrderDetail(row.id)
  } finally {
    drawer.loading = false
  }
}
</script>

<template>
  <div class="page">
    <UCard class="search-card">
      <AppForm inline @submit.prevent>
        <AppFormItem label="销售单号">
          <AppInput v-model="query.orderNo" placeholder="XS 单号" clearable @keyup.enter="search" />
        </AppFormItem>
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
            style="width: 80px"
          />
        </AppFormItem>
        <AppFormItem label="会员">
          <AppInput v-model="query.memberKeyword" placeholder="姓名/手机号" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="会员单">
          <AppSelect v-model="query.memberOnly" clearable placeholder="全部" style="width: 110px">
            <AppOption label="仅会员单" value="true" />
            <AppOption label="仅散客单" value="false" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="收款方式">
          <AppSelect v-model="query.payType" clearable placeholder="全部" style="width: 110px">
            <AppOption v-for="(label, type) in PAY_TYPE_MAP" :key="type" :label="label" :value="type" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="退货状态">
          <AppSelect v-model="query.returnStatus" clearable placeholder="全部" style="width: 110px">
            <AppOption v-for="(v, k) in RETURN_STATUS_MAP" :key="k" :label="v.label" :value="k" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="销售日期">
          <AppDatePicker
            v-model="query.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </AppFormItem>
        <AppFormItem label="金额区间">
          <AppInputNumber
            v-model="query.amountMin"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="最低"
            style="width: 90px"
          />
          <span class="range-sep">~</span>
          <AppInputNumber
            v-model="query.amountMax"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="最高"
            style="width: 90px"
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
        <span>销售单列表</span>
      </template>

      <AppTable :loading="loading" border :data="list" stripe>
        <AppTableColumn prop="orderNo" label="销售单号" width="170" fixed="left" />
        <AppTableColumn prop="counterNo" label="台号" width="60" align="center">
          <template #default="{ row }">{{ String(row.counterNo).padStart(2, '0') }}</template>
        </AppTableColumn>
        <AppTableColumn prop="cashierName" label="收银员" width="90" />
        <AppTableColumn label="会员" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.memberName">{{ row.memberName }}</span>
            <span v-else class="muted">散客</span>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="itemCount" label="件数" width="70" align="right" />
        <AppTableColumn label="商品总额" width="95" align="right">
          <template #default="{ row }">{{ money(row.goodsAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="优惠" width="85" align="right">
          <template #default="{ row }">-{{ money(row.discountAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="应收" width="95" align="right">
          <template #default="{ row }">
            <b class="payable">{{ money(row.payableAmount) }}</b>
          </template>
        </AppTableColumn>
        <AppTableColumn label="实收" width="90" align="right">
          <template #default="{ row }">{{ money(row.paidAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="找零" width="80" align="right">
          <template #default="{ row }">{{ money(row.changeAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="退货状态" width="95" align="center">
          <template #default="{ row }">
            <UBadge
              :color="tagColor(RETURN_STATUS_MAP[row.returnStatus]?.type)"
              variant="soft"
              size="sm"
            >
              {{ RETURN_STATUS_MAP[row.returnStatus]?.label ?? row.returnStatus }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="累计退款" width="90" align="right">
          <template #default="{ row }">
            <span :class="{ refunded: Number(row.refundAmount) > 0 }">{{ money(row.refundAmount) }}</span>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="saleTime" label="销售时间" width="165">
          <template #default="{ row }">{{ fmtTime(row.saleTime) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton variant="link" size="sm" label="详情" @click="openDetail(row as SalesOrderVO)" />
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

    <!-- 销售单详情（FR-SALE-09） -->
    <AppDrawer v-model="drawer.visible" title="销售单详情" size="72%">
      <div v-loading="drawer.loading">
        <template v-if="drawer.detail">
          <AppDescriptions :column="4" border size="small" class="detail-desc">
            <AppDescriptionsItem label="销售单号" :span="2">
              <b>{{ drawer.detail.orderNo }}</b>
            </AppDescriptionsItem>
            <AppDescriptionsItem label="状态">
              <UBadge :color="tagColor(RETURN_STATUS_MAP[drawer.detail.returnStatus]?.type)" variant="soft" size="sm">
                {{ RETURN_STATUS_MAP[drawer.detail.returnStatus]?.label ?? drawer.detail.returnStatus }}
              </UBadge>
            </AppDescriptionsItem>
            <AppDescriptionsItem label="销售时间">{{ fmtTime(drawer.detail.saleTime) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="收银台">{{ String(drawer.detail.counterNo).padStart(2, '0') }} 号</AppDescriptionsItem>
            <AppDescriptionsItem label="收银员">{{ drawer.detail.cashierName }}</AppDescriptionsItem>
            <AppDescriptionsItem label="会员" :span="2">
              <template v-if="drawer.detail.memberName">
                {{ drawer.detail.memberName }}（{{ drawer.detail.memberPhone }}）
              </template>
              <span v-else class="muted">散客</span>
            </AppDescriptionsItem>
            <AppDescriptionsItem label="整单折扣">
              {{ drawer.detail.orderDiscountRate != null ? Number(drawer.detail.orderDiscountRate).toFixed(2) + ' 折' : '—' }}
            </AppDescriptionsItem>
            <AppDescriptionsItem label="折扣授权人">{{ drawer.detail.authorizerName ?? '—' }}</AppDescriptionsItem>
            <AppDescriptionsItem label="折扣原因" :span="3">
              {{ drawer.detail.discountReason ?? '—' }}
            </AppDescriptionsItem>
            <AppDescriptionsItem label="商品总额">{{ money(drawer.detail.goodsAmount) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="会员优惠">-{{ money(drawer.detail.memberDiscountAmount) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="整单优惠">-{{ money(drawer.detail.orderDiscountAmount) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="应收">
              <b class="payable">{{ money(drawer.detail.payableAmount) }}</b>
            </AppDescriptionsItem>
            <AppDescriptionsItem label="实收">{{ money(drawer.detail.paidAmount) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="找零">{{ money(drawer.detail.changeAmount) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="成本合计">{{ money(drawer.detail.costAmount) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="毛利额">
              <span :class="{ profit: Number(drawer.detail.profitAmount) >= 0, loss: Number(drawer.detail.profitAmount) < 0 }">
                {{ money(drawer.detail.profitAmount) }}
              </span>
            </AppDescriptionsItem>
            <AppDescriptionsItem label="备注" :span="3">{{ drawer.detail.remark ?? '—' }}</AppDescriptionsItem>
          </AppDescriptions>

          <!-- 收款记录（FR-SALE-06） -->
          <h4 class="section-title">收款记录</h4>
          <AppTable :data="drawer.detail.payments" size="small" border>
            <AppTableColumn label="收款方式" width="120">
              <template #default="{ row }">{{ PAY_TYPE_MAP[row.payType] ?? row.payType }}</template>
            </AppTableColumn>
            <AppTableColumn label="金额" width="120" align="right">
              <template #default="{ row }">￥{{ money(row.amount) }}</template>
            </AppTableColumn>
            <AppTableColumn label="收款时间" width="170">
              <template #default="{ row }">{{ fmtTime(row.payTime) }}</template>
            </AppTableColumn>
            <AppTableColumn prop="operatorName" label="操作员" />
          </AppTable>

          <!-- 明细快照（BR-SALE-04） -->
          <h4 class="section-title">商品明细（成交快照）</h4>
          <AppTable :data="drawer.detail.items" size="small" border>
            <AppTableColumn type="index" label="#" width="44" align="center" />
            <AppTableColumn prop="goodsName" label="商品名称" min-width="160">
              <template #default="{ row }">
                {{ row.goodsName
                }}<UBadge v-if="row.lineType === 'TEMP'" size="sm" color="neutral" variant="soft" class="temp-tag">非档案商品</UBadge><span
                  v-if="row.barcode"
                  class="muted"
                >（{{ row.barcode }}）</span>
              </template>
            </AppTableColumn>
            <AppTableColumn label="成交单价" width="118" align="right">
              <template #default="{ row }">
                {{ money(row.unitPrice) }}
                <UTooltip
                  v-if="row.priceReason"
                  :text="`原价 ￥${money(row.originalUnitPrice)}｜原因：${row.priceReason}`"
                >
                  <UBadge size="sm" color="error" variant="soft">改价</UBadge>
                </UTooltip>
              </template>
            </AppTableColumn>
            <AppTableColumn label="数量" width="90" align="right">
              <template #default="{ row }">
                {{ Number(row.quantity) }}
                <UBadge v-if="row.isWeighed === 1" size="sm" color="warning" variant="soft">称重</UBadge>
              </template>
            </AppTableColumn>
            <AppTableColumn label="折扣率" width="75" align="right">
              <template #default="{ row }">
                {{ Number(row.discountRate) === 1 ? '—' : Number(row.discountRate).toFixed(2) }}
              </template>
            </AppTableColumn>
            <AppTableColumn label="行优惠" width="85" align="right">
              <template #default="{ row }">-{{ money(row.discountAmount) }}</template>
            </AppTableColumn>
            <AppTableColumn label="实付" width="90" align="right">
              <template #default="{ row }">
                <b>{{ money(row.paidAmount) }}</b>
              </template>
            </AppTableColumn>
            <AppTableColumn label="成本" width="85" align="right">
              <template #default="{ row }">{{ money(row.costAmount) }}</template>
            </AppTableColumn>
            <AppTableColumn label="毛利" width="85" align="right">
              <template #default="{ row }">
                <span :class="{ loss: Number(row.profitAmount) < 0 }">{{ money(row.profitAmount) }}</span>
              </template>
            </AppTableColumn>
            <AppTableColumn label="已退数量" width="85" align="right">
              <template #default="{ row }">
                <span v-if="Number(row.returnedQty) > 0" class="refunded">{{ Number(row.returnedQty) }}</span>
                <span v-else class="muted">—</span>
              </template>
            </AppTableColumn>
            <AppTableColumn prop="remark" label="备注" min-width="80" show-overflow-tooltip>
              <template #default="{ row }">{{ row.remark ?? '—' }}</template>
            </AppTableColumn>
          </AppTable>

          <!-- 关联退货（FR-SALE-10） -->
          <template v-if="drawer.detail.returns.length">
            <h4 class="section-title">关联退货单</h4>
            <AppTable :data="drawer.detail.returns" size="small" border>
              <AppTableColumn prop="orderNo" label="退货单号" width="170" />
              <AppTableColumn label="退款金额" width="100" align="right">
                <template #default="{ row }">￥{{ money(row.refundAmount) }}</template>
              </AppTableColumn>
              <AppTableColumn label="原因" width="90" align="center">
                <template #default="{ row }">{{ RETURN_REASON_MAP[row.returnReason] ?? row.returnReason }}</template>
              </AppTableColumn>
              <AppTableColumn label="退款方式" width="90" align="center">
                <template #default="{ row }">{{ PAY_TYPE_MAP[row.refundType] ?? row.refundType }}</template>
              </AppTableColumn>
              <AppTableColumn prop="cashierName" label="经办人" width="80" />
              <AppTableColumn prop="authorizerName" label="授权人" width="80">
                <template #default="{ row }">{{ row.authorizerName ?? '—' }}</template>
              </AppTableColumn>
              <AppTableColumn label="退货时间" width="165">
                <template #default="{ row }">{{ fmtTime(row.returnTime) }}</template>
              </AppTableColumn>
            </AppTable>
          </template>

          <!-- 操作日志（FR-SALE-09：结算/退货等留痕；GAP-P2-03） -->
          <h4 class="section-title">操作日志</h4>
          <AppTable v-if="drawer.detail.operationLogs.length" :data="drawer.detail.operationLogs" size="small" border>
            <AppTableColumn label="时间" width="170">
              <template #default="{ row }">{{ fmtTime(row.operateTime) }}</template>
            </AppTableColumn>
            <AppTableColumn prop="action" label="动作" width="130" />
            <AppTableColumn label="操作人" width="170">
              <template #default="{ row }">
                {{ row.realName || row.username }}
                <span v-if="row.realName && row.username" class="muted">（{{ row.username }}）</span>
              </template>
            </AppTableColumn>
            <AppTableColumn label="结果" width="80" align="center">
              <template #default="{ row }">
                <UBadge :color="row.result === 1 ? 'success' : 'error'" variant="soft" size="sm">
                  {{ row.result === 1 ? '成功' : '失败' }}
                </UBadge>
              </template>
            </AppTableColumn>
            <AppTableColumn label="失败原因" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.errorMsg ?? '—' }}</template>
            </AppTableColumn>
          </AppTable>
          <UEmpty v-else description="暂无操作日志" icon="i-lucide-file-text" />
        </template>
      </div>
      <template #footer>
        <UButton v-if="drawer.detail" color="neutral" variant="outline" label="打印小票" @click="printSalesReceipt(drawer.detail)" />
      </template>
    </AppDrawer>
  </div>
</template>

<style scoped lang="scss">
.page {
  .search-card {
    margin-bottom: 12px;

    :deep([data-slot='body']) {
      padding-bottom: 2px;
    }

    .range-sep {
      margin: 0 6px;
      color: var(--el-text-color-secondary);
    }
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }

  .muted {
    color: var(--el-text-color-secondary);
  }

  .temp-tag {
    margin-left: 6px;
  }

  .payable {
    color: var(--el-color-danger);
  }

  .refunded {
    color: var(--el-color-warning);
    font-weight: 500;
  }

  .profit {
    color: var(--el-color-success);
  }

  .loss {
    color: var(--el-color-danger);
  }

  .detail-desc {
    margin-bottom: 6px;
  }

  .section-title {
    margin: 18px 0 8px;
    font-size: 14px;
    color: var(--el-text-color-primary);
  }
}
</style>

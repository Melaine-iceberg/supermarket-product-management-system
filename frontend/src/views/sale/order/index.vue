<script setup lang="ts">
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
  return v == null ? '—' : Number(v).toFixed(2)
}

// ---------- 查询（FR-SALE-09） ----------
const loading = ref(false)
const list = ref<SalesOrderVO[]>([])
const total = ref(0)
const query = reactive({
  orderNo: '',
  cashierName: '',
  memberKeyword: '',
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
      memberKeyword: query.memberKeyword.trim() || undefined,
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
    memberKeyword: '',
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
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="销售单号">
          <el-input v-model="query.orderNo" placeholder="XS 单号" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="收银员">
          <el-input v-model="query.cashierName" placeholder="姓名" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="会员">
          <el-input v-model="query.memberKeyword" placeholder="姓名/手机号" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="收款方式">
          <el-select v-model="query.payType" clearable placeholder="全部" style="width: 110px">
            <el-option v-for="(label, type) in PAY_TYPE_MAP" :key="type" :label="label" :value="type" />
          </el-select>
        </el-form-item>
        <el-form-item label="退货状态">
          <el-select v-model="query.returnStatus" clearable placeholder="全部" style="width: 110px">
            <el-option v-for="(v, k) in RETURN_STATUS_MAP" :key="k" :label="v.label" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="销售日期">
          <el-date-picker
            v-model="query.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item label="金额区间">
          <el-input-number
            v-model="query.amountMin"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="最低"
            style="width: 90px"
          />
          <span class="range-sep">~</span>
          <el-input-number
            v-model="query.amountMax"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="最高"
            style="width: 90px"
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
        <span>销售单列表</span>
      </template>

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="orderNo" label="销售单号" width="170" fixed="left" />
        <el-table-column prop="counterNo" label="台号" width="60" align="center">
          <template #default="{ row }">{{ String(row.counterNo).padStart(2, '0') }}</template>
        </el-table-column>
        <el-table-column prop="cashierName" label="收银员" width="90" />
        <el-table-column label="会员" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.memberName">{{ row.memberName }}</span>
            <span v-else class="muted">散客</span>
          </template>
        </el-table-column>
        <el-table-column prop="itemCount" label="件数" width="70" align="right" />
        <el-table-column label="商品总额" width="95" align="right">
          <template #default="{ row }">{{ money(row.goodsAmount) }}</template>
        </el-table-column>
        <el-table-column label="优惠" width="85" align="right">
          <template #default="{ row }">-{{ money(row.discountAmount) }}</template>
        </el-table-column>
        <el-table-column label="应收" width="95" align="right">
          <template #default="{ row }">
            <b class="payable">{{ money(row.payableAmount) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="实收" width="90" align="right">
          <template #default="{ row }">{{ money(row.paidAmount) }}</template>
        </el-table-column>
        <el-table-column label="找零" width="80" align="right">
          <template #default="{ row }">{{ money(row.changeAmount) }}</template>
        </el-table-column>
        <el-table-column label="退货状态" width="95" align="center">
          <template #default="{ row }">
            <el-tag
              :type="RETURN_STATUS_MAP[row.returnStatus]?.type ?? 'info'"
              size="small"
            >
              {{ RETURN_STATUS_MAP[row.returnStatus]?.label ?? row.returnStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="累计退款" width="90" align="right">
          <template #default="{ row }">
            <span :class="{ refunded: Number(row.refundAmount) > 0 }">{{ money(row.refundAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="saleTime" label="销售时间" width="165">
          <template #default="{ row }">{{ fmtTime(row.saleTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row as SalesOrderVO)">详情</el-button>
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

    <!-- 销售单详情（FR-SALE-09） -->
    <el-drawer v-model="drawer.visible" title="销售单详情" size="72%">
      <div v-loading="drawer.loading">
        <template v-if="drawer.detail">
          <el-descriptions :column="4" border size="small" class="detail-desc">
            <el-descriptions-item label="销售单号" :span="2">
              <b>{{ drawer.detail.orderNo }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="RETURN_STATUS_MAP[drawer.detail.returnStatus]?.type ?? 'info'" size="small">
                {{ RETURN_STATUS_MAP[drawer.detail.returnStatus]?.label ?? drawer.detail.returnStatus }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="销售时间">{{ fmtTime(drawer.detail.saleTime) }}</el-descriptions-item>
            <el-descriptions-item label="收银台">{{ String(drawer.detail.counterNo).padStart(2, '0') }} 号</el-descriptions-item>
            <el-descriptions-item label="收银员">{{ drawer.detail.cashierName }}</el-descriptions-item>
            <el-descriptions-item label="会员" :span="2">
              <template v-if="drawer.detail.memberName">
                {{ drawer.detail.memberName }}（{{ drawer.detail.memberPhone }}）
              </template>
              <span v-else class="muted">散客</span>
            </el-descriptions-item>
            <el-descriptions-item label="整单折扣">
              {{ drawer.detail.orderDiscountRate != null ? Number(drawer.detail.orderDiscountRate).toFixed(2) + ' 折' : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="折扣授权人">{{ drawer.detail.authorizerName ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="商品总额">{{ money(drawer.detail.goodsAmount) }}</el-descriptions-item>
            <el-descriptions-item label="会员优惠">-{{ money(drawer.detail.memberDiscountAmount) }}</el-descriptions-item>
            <el-descriptions-item label="整单优惠">-{{ money(drawer.detail.orderDiscountAmount) }}</el-descriptions-item>
            <el-descriptions-item label="应收">
              <b class="payable">{{ money(drawer.detail.payableAmount) }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="实收">{{ money(drawer.detail.paidAmount) }}</el-descriptions-item>
            <el-descriptions-item label="找零">{{ money(drawer.detail.changeAmount) }}</el-descriptions-item>
            <el-descriptions-item label="成本合计">{{ money(drawer.detail.costAmount) }}</el-descriptions-item>
            <el-descriptions-item label="毛利额">
              <span :class="{ profit: Number(drawer.detail.profitAmount) >= 0, loss: Number(drawer.detail.profitAmount) < 0 }">
                {{ money(drawer.detail.profitAmount) }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="备注" :span="3">{{ drawer.detail.remark ?? '—' }}</el-descriptions-item>
          </el-descriptions>

          <!-- 收款记录（FR-SALE-06） -->
          <h4 class="section-title">收款记录</h4>
          <el-table :data="drawer.detail.payments" size="small" border>
            <el-table-column label="收款方式" width="120">
              <template #default="{ row }">{{ PAY_TYPE_MAP[row.payType] ?? row.payType }}</template>
            </el-table-column>
            <el-table-column label="金额" width="120" align="right">
              <template #default="{ row }">￥{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="收款时间" width="170">
              <template #default="{ row }">{{ fmtTime(row.payTime) }}</template>
            </el-table-column>
            <el-table-column prop="operatorName" label="操作员" />
          </el-table>

          <!-- 明细快照（BR-SALE-04） -->
          <h4 class="section-title">商品明细（成交快照）</h4>
          <el-table :data="drawer.detail.items" size="small" border>
            <el-table-column type="index" label="#" width="44" align="center" />
            <el-table-column prop="goodsName" label="商品名称" min-width="160">
              <template #default="{ row }">
                {{ row.goodsName
                }}<el-tag v-if="row.lineType === 'TEMP'" size="small" type="info" class="temp-tag">非档案商品</el-tag><span
                  v-if="row.barcode"
                  class="muted"
                >（{{ row.barcode }}）</span>
              </template>
            </el-table-column>
            <el-table-column label="成交单价" width="90" align="right">
              <template #default="{ row }">{{ money(row.unitPrice) }}</template>
            </el-table-column>
            <el-table-column label="数量" width="90" align="right">
              <template #default="{ row }">
                {{ Number(row.quantity) }}
                <el-tag v-if="row.isWeighed === 1" size="small" type="warning">称重</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="折扣率" width="75" align="right">
              <template #default="{ row }">
                {{ Number(row.discountRate) === 1 ? '—' : Number(row.discountRate).toFixed(2) }}
              </template>
            </el-table-column>
            <el-table-column label="行优惠" width="85" align="right">
              <template #default="{ row }">-{{ money(row.discountAmount) }}</template>
            </el-table-column>
            <el-table-column label="实付" width="90" align="right">
              <template #default="{ row }">
                <b>{{ money(row.paidAmount) }}</b>
              </template>
            </el-table-column>
            <el-table-column label="成本" width="85" align="right">
              <template #default="{ row }">{{ money(row.costAmount) }}</template>
            </el-table-column>
            <el-table-column label="毛利" width="85" align="right">
              <template #default="{ row }">
                <span :class="{ loss: Number(row.profitAmount) < 0 }">{{ money(row.profitAmount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="已退数量" width="85" align="right">
              <template #default="{ row }">
                <span v-if="Number(row.returnedQty) > 0" class="refunded">{{ Number(row.returnedQty) }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="80" show-overflow-tooltip>
              <template #default="{ row }">{{ row.remark ?? '—' }}</template>
            </el-table-column>
          </el-table>

          <!-- 关联退货（FR-SALE-10） -->
          <template v-if="drawer.detail.returns.length">
            <h4 class="section-title">关联退货单</h4>
            <el-table :data="drawer.detail.returns" size="small" border>
              <el-table-column prop="orderNo" label="退货单号" width="170" />
              <el-table-column label="退款金额" width="100" align="right">
                <template #default="{ row }">￥{{ money(row.refundAmount) }}</template>
              </el-table-column>
              <el-table-column label="原因" width="90" align="center">
                <template #default="{ row }">{{ RETURN_REASON_MAP[row.returnReason] ?? row.returnReason }}</template>
              </el-table-column>
              <el-table-column label="退款方式" width="90" align="center">
                <template #default="{ row }">{{ PAY_TYPE_MAP[row.refundType] ?? row.refundType }}</template>
              </el-table-column>
              <el-table-column prop="cashierName" label="经办人" width="80" />
              <el-table-column prop="authorizerName" label="授权人" width="80">
                <template #default="{ row }">{{ row.authorizerName ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="退货时间" width="165">
                <template #default="{ row }">{{ fmtTime(row.returnTime) }}</template>
              </el-table-column>
            </el-table>
          </template>
        </template>
      </div>
      <template #footer>
        <el-button v-if="drawer.detail" @click="printSalesReceipt(drawer.detail)">打印小票</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.page {
  .search-card {
    margin-bottom: 12px;

    :deep(.el-card__body) {
      padding-bottom: 2px;
    }

    .range-sep {
      margin: 0 6px;
      color: #909399;
    }
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }

  .muted {
    color: #909399;
  }

  .temp-tag {
    margin-left: 6px;
  }

  .payable {
    color: #f56c6c;
  }

  .refunded {
    color: #e6a23c;
    font-weight: 500;
  }

  .profit {
    color: #67c23a;
  }

  .loss {
    color: #f56c6c;
  }

  .detail-desc {
    margin-bottom: 6px;
  }

  .section-title {
    margin: 18px 0 8px;
    font-size: 14px;
    color: #303133;
  }
}
</style>

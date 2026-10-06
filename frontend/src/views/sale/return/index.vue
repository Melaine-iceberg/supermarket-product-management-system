<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  createReturn,
  getReturnDetail,
  getSalesOrderDetail,
  pageReturns,
  pageSalesOrders,
  PAY_TYPE_MAP,
  RETURN_REASON_MAP,
  RETURN_STATUS_MAP,
  type ReturnOrderDetailVO,
  type ReturnOrderVO,
  type SalesOrderDetailVO,
} from '@/api/sale'
import { printReturnVoucher } from '@/utils/receipt'
import { newRequestToken } from '@/utils/token'

const auth = useAuthStore()

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2)
}

/** 行退款预估（与后端 BR-SALE-06 同口径：实付×数量比，HALF_UP 到分） */
function estRefund(paidAmount: number, quantity: number, returnQty: number) {
  return Math.round(((paidAmount * returnQty) / quantity) * 100) / 100
}

// ---------- 退货单查询（FR-SALE-10） ----------
const loading = ref(false)
const list = ref<ReturnOrderVO[]>([])
const total = ref(0)
const query = reactive({
  orderNo: '',
  salesOrderNo: '',
  memberKeyword: '',
  refundType: '',
  returnReason: '',
  dateRange: [] as string[],
  page: 1,
  size: 20,
})

async function load() {
  loading.value = true
  try {
    const data = await pageReturns({
      orderNo: query.orderNo.trim() || undefined,
      salesOrderNo: query.salesOrderNo.trim() || undefined,
      memberKeyword: query.memberKeyword.trim() || undefined,
      refundType: query.refundType || undefined,
      returnReason: query.returnReason || undefined,
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
  Object.assign(query, {
    orderNo: '',
    salesOrderNo: '',
    memberKeyword: '',
    refundType: '',
    returnReason: '',
    dateRange: [],
    page: 1,
  })
  load()
}

onMounted(load)

// ---------- 退货单详情 ----------
const drawer = reactive({ visible: false, loading: false, detail: null as ReturnOrderDetailVO | null })

async function openDetail(row: ReturnOrderVO) {
  drawer.visible = true
  drawer.loading = true
  try {
    drawer.detail = await getReturnDetail(row.id)
  } finally {
    drawer.loading = false
  }
}

// ---------- 办理退货（FR-SALE-10/11） ----------
/** 退货授权金额阈值（return_auth_amount，默认 200 元，FR-SALE-11） */
const AUTH_THRESHOLD = 200

/** 退货明细行（基于原销售明细的可退信息） */
interface ReturnLine {
  salesItemId: number
  goodsName: string
  barcode: string | null
  unitPrice: number
  quantity: number
  paidAmount: number
  returnedQty: number
  /** 可退数量 */
  available: number
  selected: boolean
  returnQty: number | null
  remark: string
}

const createDialog = reactive({
  visible: false,
  step: 1 as 1 | 2,
  loading: false,
  submitting: false,
  orderKeyword: '',
  order: null as SalesOrderDetailVO | null,
  lines: [] as ReturnLine[],
  refundType: 'CASH',
  returnReason: '',
  reasonRemark: '',
  authorizerUsername: '',
  authorizerPassword: '',
  requestToken: '',
})

const canCreate = computed(() => auth.hasPerm('sys:ret:create'))

function openCreateDialog() {
  Object.assign(createDialog, {
    visible: true,
    step: 1,
    orderKeyword: '',
    order: null,
    lines: [],
    refundType: 'CASH',
    returnReason: '',
    reasonRemark: '',
    authorizerUsername: '',
    authorizerPassword: '',
    // 每次发起退货生成请求令牌（NFR-REL-02）：失败重试复用同令牌，防重复退款
    requestToken: newRequestToken(),
  })
}

/** 按销售单号查原单（BR-SALE-09 数据范围由后端校验） */
async function findOrder() {
  const kw = createDialog.orderKeyword.trim()
  if (!kw) {
    ElMessage.warning('请输入原销售单号')
    return
  }
  createDialog.loading = true
  try {
    const page = await pageSalesOrders({ orderNo: kw, page: 1, size: 1 })
    if (page.list.length === 0) {
      ElMessage.warning('未找到该销售单（或无权查看，BR-SALE-09）')
      return
    }
    const order = await getSalesOrderDetail(page.list[0].id)
    if (order.returnStatus === 'ALL') {
      ElMessage.warning('该销售单已全部退货')
      return
    }
    createDialog.order = order
    createDialog.lines = order.items
      .filter((i) => i.lineType === 'NORMAL')
      .map((i) => {
        const available = Number(i.quantity) - Number(i.returnedQty)
        return {
          salesItemId: i.id,
          goodsName: i.goodsName,
          barcode: i.barcode,
          unitPrice: Number(i.unitPrice),
          quantity: Number(i.quantity),
          paidAmount: Number(i.paidAmount),
          returnedQty: Number(i.returnedQty),
          available,
          selected: false,
          returnQty: null,
          remark: '',
        }
      })
      .filter((l) => l.available > 0)
    createDialog.step = 2
  } finally {
    createDialog.loading = false
  }
}

/** 选中行退款预估合计 */
const estTotalRefund = computed(() =>
  createDialog.lines
    .filter((l) => l.selected && l.returnQty != null && l.returnQty > 0)
    .reduce((s, l) => s + estRefund(l.paidAmount, l.quantity, l.returnQty!), 0),
)

/** 是否须店长授权（FR-SALE-11：超阈值或误操作） */
const needAuth = computed(
  () => estTotalRefund.value > AUTH_THRESHOLD || createDialog.returnReason === 'MISTAKE',
)

/** 超过退货有效期（BR-SALE-05，默认 7 天；后端最终校验） */
const expired = computed(() => {
  if (!createDialog.order) return false
  return Date.now() - new Date(createDialog.order.saleTime).getTime() > 7 * 24 * 3600 * 1000
})

function onLineCheck(line: ReturnLine, checked: boolean) {
  line.selected = checked
  if (checked && line.returnQty == null) {
    line.returnQty = line.available
  }
}

async function submitReturn() {
  const selected = createDialog.lines.filter((l) => l.selected && l.returnQty != null && l.returnQty > 0)
  if (selected.length === 0) {
    ElMessage.warning('请勾选退货明细并填写退货数量')
    return
  }
  const over = selected.find((l) => l.returnQty! > l.available)
  if (over) {
    ElMessage.warning(`【${over.goodsName}】退货数量超过可退数量 ${over.available}`)
    return
  }
  if (!createDialog.returnReason) {
    ElMessage.warning('请选择退货原因')
    return
  }
  if (needAuth.value) {
    if (!createDialog.authorizerUsername.trim()) {
      ElMessage.warning(
        createDialog.returnReason === 'MISTAKE'
          ? '误操作退货须店长授权（FR-SALE-11），请输入店长账号'
          : `退款金额超过 ${AUTH_THRESHOLD} 元须店长授权（FR-SALE-11），请输入店长账号`,
      )
      return
    }
    if (!createDialog.authorizerPassword) {
      ElMessage.warning('请输入授权密码（店长账号密码，FR-SALE-11）')
      return
    }
    if (createDialog.authorizerUsername.trim() === auth.user?.username) {
      ElMessage.warning('授权人不得为退货发起人本人（FR-SALE-11）')
      return
    }
  }
  if (!needAuth.value && expired.value && createDialog.authorizerUsername.trim()
      && !createDialog.authorizerPassword) {
    ElMessage.warning('请输入授权密码（店长账号密码）')
    return
  }
  createDialog.submitting = true
  try {
    const hasAuthorizer = needAuth.value || (expired.value && !!createDialog.authorizerUsername.trim())
    const result = await createReturn({
      salesOrderId: createDialog.order!.id,
      refundType: createDialog.refundType as 'CASH' | 'BANK' | 'MOBILE' | 'BALANCE',
      returnReason: createDialog.returnReason as 'QUALITY' | 'WRONG_ITEM' | 'MISTAKE' | 'OTHER',
      reasonRemark: createDialog.reasonRemark.trim() || undefined,
      authorizerUsername: hasAuthorizer ? createDialog.authorizerUsername.trim() : undefined,
      authorizerPassword: hasAuthorizer ? createDialog.authorizerPassword : undefined,
      items: selected.map((l) => ({
        salesItemId: l.salesItemId,
        returnQty: l.returnQty!,
        remark: l.remark.trim() || undefined,
      })),
      requestToken: createDialog.requestToken || undefined,
    })
    createDialog.visible = false
    createDialog.authorizerPassword = ''
    ElMessage.success(`退货完成：${result.orderNo}，退款 ￥${money(result.refundAmount)}`)
    load()
    offerPrintVoucher(result)
  } catch {
    // 拦截器已提示（后端校验有效期/授权/数据范围等）
  } finally {
    createDialog.submitting = false
  }
}

/** 退货办完询问是否出凭证（FR-RPT-09）：确认按钮本身是用户手势，弹窗才不会被拦截 */
function offerPrintVoucher(d: ReturnOrderDetailVO) {
  ElMessageBox.confirm('退货已办理，是否打印退货凭证？', '打印凭证', {
    confirmButtonText: '打印',
    cancelButtonText: '暂不',
    type: 'info',
  })
    .then(() => printReturnVoucher(d))
    .catch(() => undefined)
}
</script>

<template>
  <div class="page">
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="退货单号">
          <el-input v-model="query.orderNo" placeholder="XT 单号" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="原销售单号">
          <el-input v-model="query.salesOrderNo" placeholder="XS 单号" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="会员">
          <el-input v-model="query.memberKeyword" placeholder="姓名/手机号" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="退款方式">
          <el-select v-model="query.refundType" clearable placeholder="全部" style="width: 110px">
            <el-option v-for="(label, type) in PAY_TYPE_MAP" :key="type" :label="label" :value="type" />
          </el-select>
        </el-form-item>
        <el-form-item label="退货原因">
          <el-select v-model="query.returnReason" clearable placeholder="全部" style="width: 110px">
            <el-option v-for="(label, k) in RETURN_REASON_MAP" :key="k" :label="label" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="退货日期">
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
          <span>退货单列表</span>
          <el-button v-if="canCreate" type="primary" @click="openCreateDialog">
            <el-icon><RefreshLeft /></el-icon>办理退货
          </el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="orderNo" label="退货单号" width="170" fixed="left" />
        <el-table-column prop="salesOrderNo" label="原销售单号" width="170" />
        <el-table-column label="会员" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.memberName">{{ row.memberName }}</span>
            <span v-else class="muted">散客</span>
          </template>
        </el-table-column>
        <el-table-column label="退款金额" width="100" align="right">
          <template #default="{ row }">
            <b class="refund">￥{{ money(row.refundAmount) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="原因" width="90" align="center">
          <template #default="{ row }">{{ RETURN_REASON_MAP[row.returnReason] ?? row.returnReason }}</template>
        </el-table-column>
        <el-table-column label="退款方式" width="90" align="center">
          <template #default="{ row }">{{ PAY_TYPE_MAP[row.refundType] ?? row.refundType }}</template>
        </el-table-column>
        <el-table-column prop="cashierName" label="经办人" width="90" />
        <el-table-column label="授权人" width="90">
          <template #default="{ row }">{{ row.authorizerName ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="returnTime" label="退货时间" width="165">
          <template #default="{ row }">{{ fmtTime(row.returnTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row as ReturnOrderVO)">详情</el-button>
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

    <!-- 办理退货（FR-SALE-10/11） -->
    <el-dialog
      v-model="createDialog.visible"
      title="办理退货"
      width="860px"
      top="6vh"
      :close-on-click-modal="false"
    >
      <!-- 第 1 步：定位原销售单 -->
      <template v-if="createDialog.step === 1">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="输入原销售单号（XS 开头）调取原单；退款按原成交单价计算（BR-SALE-06）"
          class="dialog-alert"
        />
        <div class="find-row">
          <el-input
            v-model="createDialog.orderKeyword"
            placeholder="原销售单号，如 XS2026092001 0001"
            size="large"
            clearable
            @keyup.enter="findOrder"
          />
          <el-button type="primary" size="large" :loading="createDialog.loading" @click="findOrder">
            查询原单
          </el-button>
        </div>
      </template>

      <!-- 第 2 步：选择明细与退款信息 -->
      <template v-else-if="createDialog.order">
        <el-descriptions :column="4" border size="small" class="order-desc">
          <el-descriptions-item label="原销售单号" :span="2">
            <b>{{ createDialog.order.orderNo }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="退货状态">
            <el-tag :type="RETURN_STATUS_MAP[createDialog.order.returnStatus]?.type ?? 'info'" size="small">
              {{ RETURN_STATUS_MAP[createDialog.order.returnStatus]?.label ?? createDialog.order.returnStatus }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="销售时间">{{ fmtTime(createDialog.order.saleTime) }}</el-descriptions-item>
          <el-descriptions-item label="收银员">{{ createDialog.order.cashierName }}</el-descriptions-item>
          <el-descriptions-item label="会员">
            {{ createDialog.order.memberName ?? '散客' }}
          </el-descriptions-item>
          <el-descriptions-item label="应收">￥{{ money(createDialog.order.payableAmount) }}</el-descriptions-item>
          <el-descriptions-item label="已累计退款">￥{{ money(createDialog.order.returns.reduce((s, r) => s + Number(r.refundAmount), 0)) }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
v-if="expired" type="warning" :closable="false" show-icon class="dialog-alert"
          :title="`已超过退货有效期（默认 7 天，BR-SALE-05）：收银员不可办理，店长办理请在下方填写授权人`" />

        <el-table :data="createDialog.lines" size="small" border max-height="300">
          <el-table-column label="" width="44" align="center">
            <template #default="{ row }">
              <el-checkbox
                :model-value="row.selected"
                @change="(v: boolean | string | number) => onLineCheck(row as ReturnLine, !!v)"
              />
            </template>
          </el-table-column>
          <el-table-column prop="goodsName" label="商品" min-width="150">
            <template #default="{ row }">
              {{ row.goodsName }}<span v-if="row.barcode" class="muted">（{{ row.barcode }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="成交单价" width="85" align="right">
            <template #default="{ row }">￥{{ money(row.paidAmount / row.quantity) }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="原数量" width="75" align="right" />
          <el-table-column prop="returnedQty" label="已退" width="60" align="right" />
          <el-table-column prop="available" label="可退" width="60" align="right" />
          <el-table-column label="退货数量" width="150" align="center">
            <template #default="{ row }">
              <el-input-number
                v-model="row.returnQty"
                :min="0"
                :max="row.available"
                :precision="3"
                :controls="false"
                :disabled="!row.selected"
                size="small"
                style="width: 110px"
              />
            </template>
          </el-table-column>
          <el-table-column label="退款预估" width="95" align="right">
            <template #default="{ row }">
              <span v-if="row.selected && row.returnQty > 0">
                ￥{{ money(estRefund(row.paidAmount, row.quantity, row.returnQty)) }}
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="行备注" width="110">
            <template #default="{ row }">
              <el-input v-model="row.remark" size="small" placeholder="选填" :disabled="!row.selected" />
            </template>
          </el-table-column>
        </el-table>

        <div class="refund-form">
          <div class="refund-summary">
            退款预估合计：<b class="refund">￥{{ money(estTotalRefund) }}</b>
            <el-tag v-if="needAuth" type="danger" size="small" class="auth-tag">须店长授权</el-tag>
          </div>
          <el-form label-width="90px" inline>
            <el-form-item label="退款方式">
              <el-select v-model="createDialog.refundType" style="width: 120px">
                <el-option label="现金" value="CASH" />
                <el-option label="银行卡" value="BANK" />
                <el-option label="移动支付" value="MOBILE" />
                <el-option
                  label="退回储值"
                  value="BALANCE"
                  :disabled="!createDialog.order?.memberId"
                />
              </el-select>
              <div v-if="createDialog.order?.memberId" class="form-tip">
                会员单可选"退回储值"，退款直接回充会员余额（FR-MEM-08）
              </div>
            </el-form-item>
            <el-form-item label="退货原因">
              <el-select v-model="createDialog.returnReason" placeholder="必选" style="width: 130px">
                <el-option v-for="(label, k) in RETURN_REASON_MAP" :key="k" :label="label" :value="k" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="needAuth || expired" label="授权账号" :required="needAuth">
              <el-input
                v-model="createDialog.authorizerUsername"
                placeholder="店长登录账号"
                style="width: 150px"
              />
            </el-form-item>
            <el-form-item v-if="needAuth || expired" label="授权密码" :required="needAuth">
              <el-input
                v-model="createDialog.authorizerPassword"
                type="password"
                show-password
                maxlength="50"
                placeholder="店长账号密码"
                style="width: 150px"
              />
            </el-form-item>
            <el-form-item label="原因说明">
              <el-input v-model="createDialog.reasonRemark" placeholder="选填" style="width: 220px" />
            </el-form-item>
          </el-form>
          <div v-if="needAuth" class="auth-tip">
            退款超 {{ AUTH_THRESHOLD }} 元或原因为"误操作"须店长输入账号密码现场授权，且授权人 ≠ 发起人（FR-SALE-11）
          </div>
        </div>
      </template>

      <template #footer>
        <template v-if="createDialog.step === 1">
          <el-button @click="createDialog.visible = false">取消</el-button>
        </template>
        <template v-else>
          <el-button @click="createDialog.step = 1">上一步</el-button>
          <el-button type="primary" :loading="createDialog.submitting" @click="submitReturn">
            确认退货
          </el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 退货单详情 -->
    <el-drawer v-model="drawer.visible" title="退货单详情" size="62%">
      <div v-loading="drawer.loading">
        <template v-if="drawer.detail">
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="退货单号" :span="2">
              <b>{{ drawer.detail.orderNo }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="退货时间">{{ fmtTime(drawer.detail.returnTime) }}</el-descriptions-item>
            <el-descriptions-item label="原销售单号">{{ drawer.detail.salesOrderNo }}</el-descriptions-item>
            <el-descriptions-item label="原销售时间">{{ fmtTime(drawer.detail.salesTime) }}</el-descriptions-item>
            <el-descriptions-item label="会员">
              <template v-if="drawer.detail.memberName">
                {{ drawer.detail.memberName }}（{{ drawer.detail.memberPhone }}）
              </template>
              <span v-else class="muted">散客</span>
            </el-descriptions-item>
            <el-descriptions-item label="退款金额">
              <b class="refund">￥{{ money(drawer.detail.refundAmount) }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="退款方式">
              {{ PAY_TYPE_MAP[drawer.detail.refundType] ?? drawer.detail.refundType }}
            </el-descriptions-item>
            <el-descriptions-item label="原因">
              {{ RETURN_REASON_MAP[drawer.detail.returnReason] ?? drawer.detail.returnReason }}
              <span v-if="drawer.detail.reasonRemark" class="muted">（{{ drawer.detail.reasonRemark }}）</span>
            </el-descriptions-item>
            <el-descriptions-item label="经办人">{{ drawer.detail.cashierName }}</el-descriptions-item>
            <el-descriptions-item label="授权人">{{ drawer.detail.authorizerName ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="授权时间">{{ fmtTime(drawer.detail.authorizeTime) }}</el-descriptions-item>
          </el-descriptions>

          <h4 class="section-title">退货明细（按原成交单价退款，成本快照回冲）</h4>
          <el-table :data="drawer.detail.items" size="small" border>
            <el-table-column type="index" label="#" width="44" align="center" />
            <el-table-column prop="goodsName" label="商品" min-width="150">
              <template #default="{ row }">
                {{ row.goodsName }}<span v-if="row.barcode" class="muted">（{{ row.barcode }}）</span>
              </template>
            </el-table-column>
            <el-table-column label="退货单价" width="95" align="right">
              <template #default="{ row }">￥{{ money(row.refundPrice) }}</template>
            </el-table-column>
            <el-table-column prop="returnQty" label="退货数量" width="90" align="right" />
            <el-table-column label="退款金额" width="100" align="right">
              <template #default="{ row }">
                <b class="refund">￥{{ money(row.refundAmount) }}</b>
              </template>
            </el-table-column>
            <el-table-column label="回冲成本" width="95" align="right">
              <template #default="{ row }">￥{{ money(row.costAmount) }}</template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="90" show-overflow-tooltip>
              <template #default="{ row }">{{ row.remark ?? '—' }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
      <template #footer>
        <el-button v-if="drawer.detail" @click="printReturnVoucher(drawer.detail)">打印退货凭证</el-button>
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
  }

  .card-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }

  .muted {
    color: #909399;
  }

  .refund {
    color: #f56c6c;
  }

  .dialog-alert {
    margin-bottom: 12px;
  }

  .find-row {
    display: flex;
    gap: 10px;
    margin-top: 4px;
  }

  .order-desc {
    margin-bottom: 12px;
  }

  .refund-form {
    margin-top: 12px;

    .refund-summary {
      padding: 8px 12px;
      background: #fef0f0;
      border-radius: 6px;
      font-size: 14px;
      color: #303133;
      margin-bottom: 12px;

      b {
        font-size: 18px;
      }

      .auth-tag {
        margin-left: 10px;
      }
    }

    .auth-tip {
      font-size: 12px;
      color: #c45656;
    }
  }

  .section-title {
    margin: 18px 0 8px;
    font-size: 14px;
    color: #303133;
  }

  .form-tip {
    font-size: 12px;
    color: #909399;
    line-height: 1.4;
  }
}
</style>

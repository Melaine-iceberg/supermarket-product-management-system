<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm } from '@/utils/confirm'
import { moneyText } from '@/utils/format'
import { computed, onMounted, reactive, ref } from 'vue'
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
import { getBusinessParams, getDictItems } from '@/api/system'
import { printReturnVoucher } from '@/utils/receipt'
import { newRequestToken } from '@/utils/token'

const auth = useAuthStore()

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

onMounted(() => {
  load()
  loadBizParams()
  loadDictOptions()
})

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
/** 业务参数（BR-SALE-05、FR-SALE-11：与后端校验同源，避免硬编码；读取失败按默认值展示） */
const bizParams = reactive({ returnAuthAmount: 200, returnValidDays: 7 })

async function loadBizParams() {
  try {
    Object.assign(bizParams, await getBusinessParams())
  } catch {
    // 读取失败按默认值展示，后端办理时仍会校验
  }
}

// ---------- 退款方式 / 退货原因（FR-SYS-07：选项来自数据字典启用项，停用项不可选） ----------
/** 退款方式选项：字典读取失败/为空时回退内置展示 */
const refundTypeOptions = ref<{ label: string; value: string }[]>(
  Object.entries(PAY_TYPE_MAP).map(([value, label]) => ({ label, value })),
)
/** 退货原因选项：字典读取失败/为空时回退内置展示 */
const reasonOptions = ref<{ label: string; value: string }[]>(
  Object.entries(RETURN_REASON_MAP).map(([value, label]) => ({ label, value })),
)

async function loadDictOptions() {
  const [pays, reasons] = await Promise.allSettled([
    getDictItems('pay_type'),
    getDictItems('return_reason'),
  ])
  if (pays.status === 'fulfilled' && pays.value.length) {
    refundTypeOptions.value = pays.value.map((i) => ({ label: i.label, value: i.value }))
  }
  if (reasons.status === 'fulfilled' && reasons.value.length) {
    reasonOptions.value = reasons.value.map((i) => ({ label: i.label, value: i.value }))
  }
}

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
  /** 商品档案是否允许退货：1 是 0 否；空按允许（BR-SALE-07） */
  allowReturn: number | null
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
    notify.warning('请输入原销售单号')
    return
  }
  createDialog.loading = true
  try {
    const page = await pageSalesOrders({ orderNo: kw, page: 1, size: 1 })
    if (page.list.length === 0) {
      notify.warning('未找到该销售单（或无权查看，BR-SALE-09）')
      return
    }
    const order = await getSalesOrderDetail(page.list[0].id)
    if (order.returnStatus === 'ALL') {
      notify.warning('该销售单已全部退货')
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
          allowReturn: i.allowReturn ?? null,
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
  () => estTotalRefund.value > bizParams.returnAuthAmount || createDialog.returnReason === 'MISTAKE',
)

/** 超过退货有效期（BR-SALE-05，默认 7 天；后端最终校验） */
const expired = computed(() => {
  if (!createDialog.order) return false
  return Date.now() - new Date(createDialog.order.saleTime).getTime() > bizParams.returnValidDays * 24 * 3600 * 1000
})

function onLineCheck(line: ReturnLine, checked: boolean) {
  // 不允许退货的商品不可勾选（BR-SALE-07/GAP-P2-04）
  if (line.allowReturn === 0) return
  line.selected = checked
  if (checked && line.returnQty == null) {
    line.returnQty = line.available
  }
}

async function submitReturn() {
  const selected = createDialog.lines.filter((l) => l.selected && l.returnQty != null && l.returnQty > 0)
  if (selected.length === 0) {
    notify.warning('请勾选退货明细并填写退货数量')
    return
  }
  const over = selected.find((l) => l.returnQty! > l.available)
  if (over) {
    notify.warning(`【${over.goodsName}】退货数量超过可退数量 ${over.available}`)
    return
  }
  if (!createDialog.returnReason) {
    notify.warning('请选择退货原因')
    return
  }
  if (needAuth.value) {
    if (!createDialog.authorizerUsername.trim()) {
      notify.warning(
        createDialog.returnReason === 'MISTAKE'
          ? '误操作退货须店长授权（FR-SALE-11），请输入店长账号'
          : `退款金额超过 ${bizParams.returnAuthAmount} 元须店长授权（FR-SALE-11），请输入店长账号`,
      )
      return
    }
    if (!createDialog.authorizerPassword) {
      notify.warning('请输入授权密码（店长账号密码，FR-SALE-11）')
      return
    }
    if (createDialog.authorizerUsername.trim() === auth.user?.username) {
      notify.warning('授权人不得为退货发起人本人（FR-SALE-11）')
      return
    }
  }
  if (!needAuth.value && expired.value && createDialog.authorizerUsername.trim()
      && !createDialog.authorizerPassword) {
    notify.warning('请输入授权密码（店长账号密码）')
    return
  }
  createDialog.submitting = true
  try {
    const hasAuthorizer = needAuth.value || (expired.value && !!createDialog.authorizerUsername.trim())
    const result = await createReturn({
      salesOrderId: createDialog.order!.id,
      refundType: createDialog.refundType,
      returnReason: createDialog.returnReason,
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
    notify.success(`退货完成：${result.orderNo}，退款 ￥${money(result.refundAmount)}`)
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
  confirm('退货已办理，是否打印退货凭证？', '打印凭证', {
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
    <UCard class="search-card">
      <AppForm inline @submit.prevent>
        <AppFormItem label="退货单号">
          <AppInput v-model="query.orderNo" placeholder="XT 单号" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="原销售单号">
          <AppInput v-model="query.salesOrderNo" placeholder="XS 单号" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="会员">
          <AppInput v-model="query.memberKeyword" placeholder="姓名/手机号" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="退款方式">
          <AppSelect v-model="query.refundType" clearable placeholder="全部" style="width: 110px">
            <AppOption v-for="(label, type) in PAY_TYPE_MAP" :key="type" :label="label" :value="type" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="退货原因">
          <AppSelect v-model="query.returnReason" clearable placeholder="全部" style="width: 110px">
            <AppOption v-for="(label, k) in RETURN_REASON_MAP" :key="k" :label="label" :value="k" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="退货日期">
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
          <span>退货单列表</span>
          <UButton
            v-if="canCreate"
            icon="i-lucide-rotate-ccw"
            label="办理退货"
            @click="openCreateDialog"
          />
        </div>
      </template>

      <AppTable :loading="loading" border :data="list" stripe>
        <AppTableColumn prop="orderNo" label="退货单号" width="170" fixed="left" />
        <AppTableColumn prop="salesOrderNo" label="原销售单号" width="170" />
        <AppTableColumn label="会员" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.memberName">{{ row.memberName }}</span>
            <span v-else class="muted">散客</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="退款金额" width="100" align="right">
          <template #default="{ row }">
            <b class="refund">￥{{ money(row.refundAmount) }}</b>
          </template>
        </AppTableColumn>
        <AppTableColumn label="原因" width="90" align="center">
          <template #default="{ row }">{{ RETURN_REASON_MAP[row.returnReason] ?? row.returnReason }}</template>
        </AppTableColumn>
        <AppTableColumn label="退款方式" width="90" align="center">
          <template #default="{ row }">{{ PAY_TYPE_MAP[row.refundType] ?? row.refundType }}</template>
        </AppTableColumn>
        <AppTableColumn prop="cashierName" label="经办人" width="90" />
        <AppTableColumn label="授权人" width="90">
          <template #default="{ row }">{{ row.authorizerName ?? '—' }}</template>
        </AppTableColumn>
        <AppTableColumn prop="returnTime" label="退货时间" width="165">
          <template #default="{ row }">{{ fmtTime(row.returnTime) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <UButton variant="link" size="sm" label="详情" @click="openDetail(row as ReturnOrderVO)" />
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

    <!-- 办理退货（FR-SALE-10/11） -->
    <AppModal
      v-model="createDialog.visible"
      title="办理退货"
      width="860px"
      :close-on-click-modal="false"
    >
      <!-- 第 1 步：定位原销售单 -->
      <template v-if="createDialog.step === 1">
        <UAlert
          color="info"
          icon="i-lucide-info"
          title="输入原销售单号（XS 开头）调取原单；退款按原成交单价计算（BR-SALE-06）"
          class="dialog-alert"
        />
        <div class="find-row">
          <AppInput
            v-model="createDialog.orderKeyword"
            placeholder="原销售单号，如 XS2026092001 0001"
            size="large"
            clearable
            @keyup.enter="findOrder"
          />
          <UButton size="lg" label="查询原单" :loading="createDialog.loading" @click="findOrder" />
        </div>
      </template>

      <!-- 第 2 步：选择明细与退款信息 -->
      <template v-else-if="createDialog.order">
        <AppDescriptions :column="4" border size="small" class="order-desc">
          <AppDescriptionsItem label="原销售单号" :span="2">
            <b>{{ createDialog.order.orderNo }}</b>
          </AppDescriptionsItem>
          <AppDescriptionsItem label="退货状态">
            <UBadge :color="tagColor(RETURN_STATUS_MAP[createDialog.order.returnStatus]?.type)" variant="soft" size="sm">
              {{ RETURN_STATUS_MAP[createDialog.order.returnStatus]?.label ?? createDialog.order.returnStatus }}
            </UBadge>
          </AppDescriptionsItem>
          <AppDescriptionsItem label="销售时间">{{ fmtTime(createDialog.order.saleTime) }}</AppDescriptionsItem>
          <AppDescriptionsItem label="收银员">{{ createDialog.order.cashierName }}</AppDescriptionsItem>
          <AppDescriptionsItem label="会员">
            {{ createDialog.order.memberName ?? '散客' }}
          </AppDescriptionsItem>
          <AppDescriptionsItem label="应收">￥{{ money(createDialog.order.payableAmount) }}</AppDescriptionsItem>
          <AppDescriptionsItem label="已累计退款">￥{{ money(createDialog.order.returns.reduce((s, r) => s + Number(r.refundAmount), 0)) }}</AppDescriptionsItem>
        </AppDescriptions>

        <UAlert
          v-if="expired"
          color="warning"
          icon="i-lucide-triangle-alert"
          class="dialog-alert"
          :title="`已超过退货有效期（${bizParams.returnValidDays} 天，BR-SALE-05）：收银员不可办理，店长办理请在下方填写授权人`"
        />

        <AppTable :data="createDialog.lines" size="small" border max-height="300">
          <AppTableColumn label="" width="44" align="center">
            <template #default="{ row }">
              <UTooltip
                v-if="row.allowReturn === 0"
                text="该商品不支持退货（BR-SALE-07：生鲜/散装等）"
              >
                <UCheckbox :model-value="false" disabled />
              </UTooltip>
              <UCheckbox
                v-else
                :model-value="row.selected"
                @update:model-value="(v: boolean | 'indeterminate') => onLineCheck(row as ReturnLine, !!v)"
              />
            </template>
          </AppTableColumn>
          <AppTableColumn prop="goodsName" label="商品" min-width="150">
            <template #default="{ row }">
              {{ row.goodsName }}<span v-if="row.barcode" class="muted">（{{ row.barcode }}）</span>
              <UBadge v-if="row.allowReturn === 0" size="sm" color="neutral" variant="soft" class="no-return-tag">不允许退货</UBadge>
            </template>
          </AppTableColumn>
          <AppTableColumn label="成交单价" width="85" align="right">
            <template #default="{ row }">￥{{ money(row.paidAmount / row.quantity) }}</template>
          </AppTableColumn>
          <AppTableColumn prop="quantity" label="原数量" width="75" align="right" />
          <AppTableColumn prop="returnedQty" label="已退" width="60" align="right" />
          <AppTableColumn prop="available" label="可退" width="60" align="right" />
          <AppTableColumn label="退货数量" width="150" align="center">
            <template #default="{ row }">
              <AppInputNumber
                v-model="row.returnQty"
                :min="0"
                :max="row.available"
                :precision="3"
                :controls="false"
                :disabled="!row.selected || row.allowReturn === 0"
                size="small"
                style="width: 110px"
              />
            </template>
          </AppTableColumn>
          <AppTableColumn label="退款预估" width="95" align="right">
            <template #default="{ row }">
              <span v-if="row.selected && row.returnQty > 0">
                ￥{{ money(estRefund(row.paidAmount, row.quantity, row.returnQty)) }}
              </span>
              <span v-else class="muted">—</span>
            </template>
          </AppTableColumn>
          <AppTableColumn label="行备注" width="110">
            <template #default="{ row }">
              <AppInput v-model="row.remark" size="small" placeholder="选填" :disabled="!row.selected" />
            </template>
          </AppTableColumn>
        </AppTable>

        <div class="refund-form">
          <div class="refund-summary">
            退款预估合计：<b class="refund">￥{{ money(estTotalRefund) }}</b>
            <UBadge v-if="needAuth" color="error" variant="soft" size="sm" class="auth-tag">须店长授权</UBadge>
          </div>
          <AppForm label-width="90px" inline>
            <AppFormItem label="退款方式">
              <AppSelect v-model="createDialog.refundType" style="width: 120px">
                <AppOption
                  v-for="opt in refundTypeOptions"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                  :disabled="opt.value === 'BALANCE' && !createDialog.order?.memberId"
                />
              </AppSelect>
              <div v-if="createDialog.order?.memberId" class="form-tip">
                会员单可选储值退款，退款直接回充会员余额（FR-MEM-08）
              </div>
            </AppFormItem>
            <AppFormItem label="退货原因">
              <AppSelect v-model="createDialog.returnReason" placeholder="必选" style="width: 130px">
                <AppOption v-for="opt in reasonOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </AppSelect>
            </AppFormItem>
            <AppFormItem v-if="needAuth || expired" label="授权账号" :required="needAuth">
              <AppInput
                v-model="createDialog.authorizerUsername"
                placeholder="店长登录账号"
                style="width: 150px"
              />
            </AppFormItem>
            <AppFormItem v-if="needAuth || expired" label="授权密码" :required="needAuth">
              <AppInput
                v-model="createDialog.authorizerPassword"
                type="password"
                show-password
                maxlength="50"
                placeholder="店长账号密码"
                style="width: 150px"
              />
            </AppFormItem>
            <AppFormItem label="原因说明">
              <AppInput v-model="createDialog.reasonRemark" placeholder="选填" style="width: 220px" />
            </AppFormItem>
          </AppForm>
          <div v-if="needAuth" class="auth-tip">
            退款超 {{ bizParams.returnAuthAmount }} 元或原因为"误操作"须店长输入账号密码现场授权，且授权人 ≠ 发起人（FR-SALE-11）
          </div>
        </div>
      </template>

      <template #footer>
        <template v-if="createDialog.step === 1">
          <UButton color="neutral" variant="outline" label="取消" @click="createDialog.visible = false" />
        </template>
        <template v-else>
          <UButton color="neutral" variant="outline" label="上一步" @click="createDialog.step = 1" />
          <UButton
            label="确认退货"
            :loading="createDialog.submitting"
            @click="submitReturn"
          />
        </template>
      </template>
    </AppModal>

    <!-- 退货单详情 -->
    <AppDrawer v-model="drawer.visible" title="退货单详情" size="62%">
      <div v-loading="drawer.loading">
        <template v-if="drawer.detail">
          <AppDescriptions :column="3" border size="small">
            <AppDescriptionsItem label="退货单号" :span="2">
              <b>{{ drawer.detail.orderNo }}</b>
            </AppDescriptionsItem>
            <AppDescriptionsItem label="退货时间">{{ fmtTime(drawer.detail.returnTime) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="原销售单号">{{ drawer.detail.salesOrderNo }}</AppDescriptionsItem>
            <AppDescriptionsItem label="原销售时间">{{ fmtTime(drawer.detail.salesTime) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="会员">
              <template v-if="drawer.detail.memberName">
                {{ drawer.detail.memberName }}（{{ drawer.detail.memberPhone }}）
              </template>
              <span v-else class="muted">散客</span>
            </AppDescriptionsItem>
            <AppDescriptionsItem label="退款金额">
              <b class="refund">￥{{ money(drawer.detail.refundAmount) }}</b>
            </AppDescriptionsItem>
            <AppDescriptionsItem label="退款方式">
              {{ PAY_TYPE_MAP[drawer.detail.refundType] ?? drawer.detail.refundType }}
            </AppDescriptionsItem>
            <AppDescriptionsItem label="原因">
              {{ RETURN_REASON_MAP[drawer.detail.returnReason] ?? drawer.detail.returnReason }}
              <span v-if="drawer.detail.reasonRemark" class="muted">（{{ drawer.detail.reasonRemark }}）</span>
            </AppDescriptionsItem>
            <AppDescriptionsItem label="经办人">{{ drawer.detail.cashierName }}</AppDescriptionsItem>
            <AppDescriptionsItem label="授权人">{{ drawer.detail.authorizerName ?? '—' }}</AppDescriptionsItem>
            <AppDescriptionsItem label="授权时间">{{ fmtTime(drawer.detail.authorizeTime) }}</AppDescriptionsItem>
          </AppDescriptions>

          <h4 class="section-title">退货明细（按原成交单价退款，成本快照回冲）</h4>
          <AppTable :data="drawer.detail.items" size="small" border>
            <AppTableColumn type="index" label="#" width="44" align="center" />
            <AppTableColumn prop="goodsName" label="商品" min-width="150">
              <template #default="{ row }">
                {{ row.goodsName }}<span v-if="row.barcode" class="muted">（{{ row.barcode }}）</span>
              </template>
            </AppTableColumn>
            <AppTableColumn label="退货单价" width="95" align="right">
              <template #default="{ row }">￥{{ money(row.refundPrice) }}</template>
            </AppTableColumn>
            <AppTableColumn prop="returnQty" label="退货数量" width="90" align="right" />
            <AppTableColumn label="退款金额" width="100" align="right">
              <template #default="{ row }">
                <b class="refund">￥{{ money(row.refundAmount) }}</b>
              </template>
            </AppTableColumn>
            <AppTableColumn label="回冲成本" width="95" align="right">
              <template #default="{ row }">￥{{ money(row.costAmount) }}</template>
            </AppTableColumn>
            <AppTableColumn prop="remark" label="备注" min-width="90" show-overflow-tooltip>
              <template #default="{ row }">{{ row.remark ?? '—' }}</template>
            </AppTableColumn>
          </AppTable>
        </template>
      </div>
      <template #footer>
        <UButton
          v-if="drawer.detail"
          color="neutral"
          variant="outline"
          label="打印退货凭证"
          @click="printReturnVoucher(drawer.detail)"
        />
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
    color: var(--el-text-color-secondary);
  }

  .refund {
    color: var(--el-color-danger);
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

  .no-return-tag {
    margin-left: 6px;
  }

  .refund-form {
    margin-top: 12px;

    .refund-summary {
      padding: 8px 12px;
      background: var(--el-color-danger-light-9);
      border-radius: 6px;
      font-size: 14px;
      color: var(--el-text-color-primary);
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
      color: var(--el-color-danger-dark-2);
    }
  }

  .section-title {
    margin: 18px 0 8px;
    font-size: 14px;
    color: var(--el-text-color-primary);
  }

  .form-tip {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    line-height: 1.4;
  }
}
</style>

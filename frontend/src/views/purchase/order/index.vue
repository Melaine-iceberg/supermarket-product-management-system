<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import {
  PURCHASE_STATUS_MAP,
  RETURN_STATUS_MAP,
  SETTLE_METHOD_MAP,
  SETTLE_STATUS_MAP,
  auditPurchaseOrder,
  auditPurchaseReturn,
  cancelPurchaseOrder,
  closePurchaseOrder,
  createPurchaseOrder,
  createPurchaseReturn,
  deletePurchaseOrder,
  getPurchaseOrderDetail,
  getPurchaseReturnDetail,
  pagePurchaseOrders,
  pagePurchaseReturns,
  settlePurchaseOrder,
  submitPurchaseOrder,
  terminatePurchaseOrder,
  updatePurchaseOrder,
  withdrawPurchaseOrder,
  type PurchaseOrderDetailVO,
  type PurchaseOrderItemVO,
  type PurchaseOrderVO,
  type PurchaseReturnVO,
} from '@/api/purchase'
import { pageSuppliers, type SupplierListVO } from '@/api/supplier'
import { pageGoods, type GoodsListVO } from '@/api/goods'
import { pMoney, pQty, printSheet } from '@/utils/print'

const auth = useAuthStore()
const route = useRoute()

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2)
}

function statusTag(status: string) {
  return PURCHASE_STATUS_MAP[status] ?? { label: status, type: 'info' as const }
}

/** 可发起退货的采购单状态（状态—操作约束矩阵） */
function returnable(row: PurchaseOrderVO) {
  return ['PARTIAL', 'COMPLETED', 'PARTIAL_RETURNED'].includes(row.status)
}

/** 可登记结算的采购单状态（FR-PUR-09） */
function settlable(row: PurchaseOrderVO) {
  return ['PARTIAL', 'COMPLETED', 'PARTIAL_RETURNED', 'ALL_RETURNED'].includes(row.status)
}

// ---------- 供应商下拉 ----------
const suppliers = ref<SupplierListVO[]>([])

async function loadSuppliers() {
  const data = await pageSuppliers({ status: 1, page: 1, size: 200 })
  suppliers.value = data.list
}

// ==================== 采购单列表（FR-PUR-03） ====================
const activeTab = ref('orders')
const loading = ref(false)
const list = ref<PurchaseOrderVO[]>([])
const total = ref(0)
const createDateRange = ref<[string, string] | null>(null)
const query = reactive({
  orderNo: '',
  supplierId: null as number | null,
  status: '' as string,
  createBy: '',
  goodsKeyword: '',
  page: 1,
  size: 20,
})

async function load() {
  loading.value = true
  try {
    const data = await pagePurchaseOrders({
      orderNo: query.orderNo.trim() || undefined,
      supplierId: query.supplierId ?? undefined,
      status: query.status || undefined,
      createBy: query.createBy.trim() || undefined,
      goodsKeyword: query.goodsKeyword.trim() || undefined,
      createDateBegin: createDateRange.value?.[0],
      createDateEnd: createDateRange.value?.[1],
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
  Object.assign(query, { orderNo: '', supplierId: null, status: '', createBy: '', goodsKeyword: '', page: 1 })
  createDateRange.value = null
  load()
}

// ==================== 详情（FR-PUR-03） ====================
const detailDrawer = reactive({
  visible: false,
  loading: false,
  data: null as PurchaseOrderDetailVO | null,
})

async function openDetail(row: PurchaseOrderVO) {
  detailDrawer.visible = true
  detailDrawer.loading = true
  try {
    detailDrawer.data = await getPurchaseOrderDetail(row.id)
  } finally {
    detailDrawer.loading = false
  }
}

/** 采购单 A4 打印（FR-RPT-09、X-09）：线下发送供应商，只出订购口径，不含入库/退货进度等内部信息 */
function printOrder() {
  const d = detailDrawer.data
  if (!d) return
  printSheet<PurchaseOrderItemVO>({
    title: '采购单',
    meta: [
      `单号：${d.orderNo}`,
      `供应商：${d.supplierName}`,
      `状态：${statusTag(d.status).label}`,
      `预计到货：${d.expectDate ?? '—'}`,
      `制单人：${d.createBy}｜制单时间：${fmtTime(d.createTime)}`,
      d.remark ? `备注：${d.remark}` : null,
    ],
    columns: [
      { label: '序号', value: (_r, i) => i + 1, align: 'center' },
      { label: '商品编码', value: (r) => r.goodsCode },
      { label: '商品名称', value: (r) => r.goodsName },
      { label: '规格', value: (r) => r.spec },
      { label: '单位', value: (r) => r.unitName, align: 'center' },
      { label: '订购数量', value: (r) => pQty(r.quantity), align: 'right' },
      { label: '采购单价', value: (r) => pMoney(r.price), align: 'right' },
      { label: '金额', value: (r) => pMoney(r.amount), align: 'right' },
      { label: '备注', value: (r) => r.remark },
    ],
    rows: d.items,
    totals: [`合计金额：￥${pMoney(d.totalAmount)}`],
    signs: ['制单人：____________', '审核人：____________', '供应商签章：____________'],
  })
}

// ==================== 新建 / 编辑（FR-PUR-01） ====================
interface EditItem {
  goodsId: number
  goodsCode: string
  goodsName: string
  unitName: string
  quantity: number | null
  price: number | null
  remark: string
}

const editDialog = reactive({
  visible: false,
  saving: false,
  editing: false,
  id: 0,
  supplierId: null as number | null,
  expectDate: '',
  remark: '',
  items: [] as EditItem[],
})

/** 明细行金额 */
function lineAmount(item: EditItem) {
  if (item.quantity == null || item.price == null) return 0
  return Number(item.quantity) * Number(item.price)
}

const totalAmount = computed(() =>
  editDialog.items.reduce((sum, item) => sum + lineAmount(item), 0),
)

function openCreate() {
  editDialog.editing = false
  editDialog.id = 0
  editDialog.supplierId = null
  editDialog.expectDate = ''
  editDialog.remark = ''
  editDialog.items = []
  editDialog.visible = true
}

async function openEdit(row: PurchaseOrderVO) {
  const detail = await getPurchaseOrderDetail(row.id)
  editDialog.editing = true
  editDialog.id = row.id
  editDialog.supplierId = detail.supplierId
  editDialog.expectDate = detail.expectDate ?? ''
  editDialog.remark = detail.remark ?? ''
  editDialog.items = detail.items.map((i: PurchaseOrderItemVO) => ({
    goodsId: i.goodsId,
    goodsCode: i.goodsCode,
    goodsName: i.goodsName,
    unitName: i.unitName ?? '',
    quantity: Number(i.quantity),
    price: Number(i.price),
    remark: i.remark ?? '',
  }))
  editDialog.visible = true
}

/** 商品搜索弹窗：从商品档案选行（仅启用商品，BR-PUR-03） */
const goodsPicker = reactive({
  visible: false,
  keyword: '',
  loading: false,
  list: [] as GoodsListVO[],
})

function openGoodsPicker() {
  goodsPicker.keyword = ''
  goodsPicker.visible = true
  searchGoods()
}

async function searchGoods() {
  goodsPicker.loading = true
  try {
    const data = await pageGoods({
      name: goodsPicker.keyword.trim() || undefined,
      goodsCode: goodsPicker.keyword.trim() || undefined,
      status: 1,
      page: 1,
      size: 50,
    })
    goodsPicker.list = data.list
  } finally {
    goodsPicker.loading = false
  }
}

function pickGoods(goods: GoodsListVO) {
  if (editDialog.items.some((i) => i.goodsId === goods.id)) {
    ElMessage.warning('该商品已在明细中（同商品仅一行，BR-PUR-03）')
    return
  }
  editDialog.items.push({
    goodsId: goods.id,
    goodsCode: goods.goodsCode,
    goodsName: goods.name,
    unitName: goods.unitName,
    quantity: 1,
    price: goods.purchasePrice != null ? Number(goods.purchasePrice) : 0,
    remark: '',
  })
  goodsPicker.visible = false
}

function removeItem(index: number) {
  editDialog.items.splice(index, 1)
}

async function saveOrder() {
  if (editDialog.supplierId == null) {
    ElMessage.warning('请选择供应商')
    return
  }
  if (editDialog.items.length === 0) {
    ElMessage.warning('请至少添加一条采购明细（BR-PUR-03）')
    return
  }
  if (editDialog.items.some((i) => !i.quantity || i.quantity <= 0 || i.price == null || i.price < 0)) {
    ElMessage.warning('明细数量必须大于 0、单价不能为负（BR-PUR-03）')
    return
  }
  editDialog.saving = true
  try {
    const payload = {
      supplierId: editDialog.supplierId,
      expectDate: editDialog.expectDate || undefined,
      remark: editDialog.remark || undefined,
      items: editDialog.items.map((i) => ({
        goodsId: i.goodsId,
        quantity: Number(i.quantity),
        price: Number(i.price),
        remark: i.remark || undefined,
      })),
    }
    if (editDialog.editing) {
      await updatePurchaseOrder(editDialog.id, payload)
      ElMessage.success('采购单已保存')
    } else {
      await createPurchaseOrder(payload)
      ElMessage.success('采购单草稿已创建')
    }
    editDialog.visible = false
    load()
  } finally {
    editDialog.saving = false
  }
}

// ==================== 提交 / 撤回 / 审核（FR-PUR-04） ====================
async function submitOrder(row: PurchaseOrderVO) {
  await ElMessageBox.confirm(`确认提交采购单 ${row.orderNo} 审核？提交后由店长审核`, '提交审核', { type: 'info' })
  await submitPurchaseOrder(row.id)
  ElMessage.success('已提交审核')
  load()
}

async function withdrawOrder(row: PurchaseOrderVO) {
  await ElMessageBox.confirm(`确认撤回采购单 ${row.orderNo}？撤回后回到草稿可继续编辑`, '撤回', { type: 'warning' })
  await withdrawPurchaseOrder(row.id)
  ElMessage.success('已撤回')
  load()
}

/** 审核弹窗：通过 / 驳回（驳回必填意见，BR-PUR-02） */
const auditDialog = reactive({
  visible: false,
  saving: false,
  id: 0,
  orderNo: '',
  approved: true,
  remark: '',
})

function openAudit(row: PurchaseOrderVO) {
  auditDialog.id = row.id
  auditDialog.orderNo = row.orderNo
  auditDialog.approved = true
  auditDialog.remark = ''
  auditDialog.visible = true
}

async function saveAudit() {
  if (!auditDialog.approved && !auditDialog.remark.trim()) {
    ElMessage.warning('驳回时必须填写审核意见（FR-PUR-04）')
    return
  }
  auditDialog.saving = true
  try {
    await auditPurchaseOrder(auditDialog.id, auditDialog.approved, auditDialog.remark.trim() || undefined)
    ElMessage.success(auditDialog.approved ? '审核通过' : '已驳回退回草稿')
    auditDialog.visible = false
    load()
  } finally {
    auditDialog.saving = false
  }
}

// ==================== 作废 / 终止 / 关闭（FR-PUR-05） ====================
async function cancelOrder(row: PurchaseOrderVO) {
  const { value } = await ElMessageBox.prompt('作废后单据保留可查但不可再流转，请填写作废原因', `作废 ${row.orderNo}`, {
    inputPlaceholder: '作废原因（必填）',
    inputValidator: (v: string) => (v && v.trim() ? true : '原因不能为空'),
  })
  await cancelPurchaseOrder(row.id, value.trim())
  ElMessage.success('已作废')
  load()
}

async function terminateOrder(row: PurchaseOrderVO) {
  const { value } = await ElMessageBox.prompt('终止已审核未入库的采购单，在途量将同步扣除，请填写终止原因', `终止 ${row.orderNo}`, {
    inputPlaceholder: '终止原因（必填）',
    inputValidator: (v: string) => (v && v.trim() ? true : '原因不能为空'),
  })
  await terminatePurchaseOrder(row.id, value.trim())
  ElMessage.success('已终止')
  load()
}

async function closeOrder(row: PurchaseOrderVO) {
  const { value } = await ElMessageBox.prompt('关闭后剩余数量不再入库，单据转已完成，请填写短少原因', `关闭短少 ${row.orderNo}`, {
    inputPlaceholder: '短少原因（必填）',
    inputValidator: (v: string) => (v && v.trim() ? true : '原因不能为空'),
  })
  await closePurchaseOrder(row.id, value.trim())
  ElMessage.success('已关闭剩余数量')
  load()
}

async function removeOrder(row: PurchaseOrderVO) {
  await ElMessageBox.confirm(`确认删除草稿 ${row.orderNo}？删除后不可恢复`, '删除草稿', { type: 'warning' })
  await deletePurchaseOrder(row.id)
  ElMessage.success('已删除')
  load()
}

// ==================== 结算登记（FR-PUR-09） ====================
const settleDialog = reactive({
  visible: false,
  saving: false,
  id: 0,
  orderNo: '',
  totalAmount: 0,
  receivedAmount: 0,
  returnAmount: 0,
  form: {
    settleStatus: 'SETTLED',
    settleAmount: 0 as number | null,
    settleMethod: 'CASH',
    settleDate: '',
  },
})

function openSettle(row: PurchaseOrderVO) {
  settleDialog.id = row.id
  settleDialog.orderNo = row.orderNo
  settleDialog.totalAmount = Number(row.totalAmount)
  settleDialog.receivedAmount = Number(row.receivedAmount)
  settleDialog.returnAmount = Number(row.returnAmount)
  settleDialog.form = {
    settleStatus: 'SETTLED',
    settleAmount: Number(row.receivedAmount) - Number(row.returnAmount),
    settleMethod: 'CASH',
    settleDate: new Date().toISOString().slice(0, 10),
  }
  settleDialog.visible = true
}

async function saveSettle() {
  if (settleDialog.form.settleStatus !== 'UNSETTLED' && settleDialog.form.settleAmount == null) {
    ElMessage.warning('请填写结算金额')
    return
  }
  settleDialog.saving = true
  try {
    await settlePurchaseOrder(settleDialog.id, {
      settleStatus: settleDialog.form.settleStatus,
      settleAmount: settleDialog.form.settleAmount ?? 0,
      settleMethod: settleDialog.form.settleStatus === 'UNSETTLED' ? undefined : settleDialog.form.settleMethod,
      settleDate: settleDialog.form.settleStatus === 'UNSETTLED' ? undefined : settleDialog.form.settleDate || undefined,
    })
    ElMessage.success('结算登记完成')
    settleDialog.visible = false
    load()
  } finally {
    settleDialog.saving = false
  }
}

// ==================== 采购退货（FR-PUR-08） ====================
const returnTabLoading = ref(false)
const returnList = ref<PurchaseReturnVO[]>([])
const returnTotal = ref(0)
const returnDateRange = ref<[string, string] | null>(null)
const returnQuery = reactive({
  orderNo: '',
  purchaseOrderNo: '',
  status: '' as string,
  page: 1,
  size: 20,
})

async function loadReturns() {
  returnTabLoading.value = true
  try {
    const data = await pagePurchaseReturns({
      orderNo: returnQuery.orderNo.trim() || undefined,
      purchaseOrderNo: returnQuery.purchaseOrderNo.trim() || undefined,
      status: returnQuery.status || undefined,
      beginDate: returnDateRange.value?.[0],
      endDate: returnDateRange.value?.[1],
      page: returnQuery.page,
      size: returnQuery.size,
    })
    returnList.value = data.list
    returnTotal.value = data.total
  } finally {
    returnTabLoading.value = false
  }
}

function searchReturns() {
  returnQuery.page = 1
  loadReturns()
}

function onTabChange(tab: string | number) {
  if (tab === 'returns') loadReturns()
}

/** 退货单详情 */
const returnDrawer = reactive({
  visible: false,
  loading: false,
  data: null as PurchaseReturnVO | null,
})

async function openReturnDetail(row: PurchaseReturnVO) {
  returnDrawer.visible = true
  returnDrawer.loading = true
  try {
    returnDrawer.data = await getPurchaseReturnDetail(row.id)
  } finally {
    returnDrawer.loading = false
  }
}

/** 创建退货：勾选明细行 + 退货数量（≤ 已入库 − 已退）+ 原因 */
const returnDialog = reactive({
  visible: false,
  saving: false,
  purchaseOrderNo: '',
  orderId: 0,
  returnReason: '',
  remark: '',
  items: [] as { purchaseItemId: number; goodsName: string; unitName: string; returnable: number; returnQty: number | null; returnedPrice: number }[],
})

async function openReturn(row: PurchaseOrderVO) {
  const detail = await getPurchaseOrderDetail(row.id)
  returnDialog.orderId = row.id
  returnDialog.purchaseOrderNo = row.orderNo
  returnDialog.returnReason = ''
  returnDialog.remark = ''
  returnDialog.items = detail.items
    .filter((i: PurchaseOrderItemVO) => Number(i.receivedQty) - Number(i.returnedQty) > 0)
    .map((i: PurchaseOrderItemVO) => ({
      purchaseItemId: i.id,
      goodsName: `${i.goodsName}${i.spec ? '（' + i.spec + '）' : ''}`,
      unitName: i.unitName ?? '',
      returnable: Number(i.receivedQty) - Number(i.returnedQty),
      returnQty: null,
      returnedPrice: Number(i.price),
    }))
  if (returnDialog.items.length === 0) {
    ElMessage.warning('该采购单没有可退商品（可退 = 已入库 − 已退）')
    return
  }
  returnDialog.visible = true
}

async function saveReturn() {
  const selected = returnDialog.items.filter((i) => i.returnQty != null && i.returnQty > 0)
  if (selected.length === 0) {
    ElMessage.warning('请填写退货数量')
    return
  }
  if (selected.some((i) => i.returnQty! > i.returnable)) {
    ElMessage.warning('退货数量不能超过可退数量（已入库 − 已退）')
    return
  }
  if (!returnDialog.returnReason.trim()) {
    ElMessage.warning('请填写退货原因')
    return
  }
  returnDialog.saving = true
  try {
    await createPurchaseReturn({
      purchaseOrderId: returnDialog.orderId,
      returnReason: returnDialog.returnReason.trim(),
      remark: returnDialog.remark || undefined,
      items: selected.map((i) => ({ purchaseItemId: i.purchaseItemId, returnQty: Number(i.returnQty) })),
    })
    ElMessage.success('退货单已创建，待店长审核')
    returnDialog.visible = false
    load()
  } finally {
    returnDialog.saving = false
  }
}

/** 退货审核：通过执行退货 / 驳回须意见 */
async function auditReturn(row: PurchaseReturnVO) {
  const { value } = await ElMessageBox.prompt(
    `审核退货单 ${row.orderNo}：输入"通过"执行退货（生成负数流水并冲减结存），或填写意见驳回`,
    '退货审核',
    {
      inputPlaceholder: '通过 / 驳回意见',
      inputValue: '通过',
      inputValidator: (v: string) => (v && v.trim() ? true : '请输入审核结论'),
    },
  )
  const approved = value.trim() === '通过'
  if (!approved && value.trim().length < 2) {
    ElMessage.warning('驳回时必须填写审核意见')
    return
  }
  await auditPurchaseReturn(row.id, approved, approved ? undefined : value.trim())
  ElMessage.success(approved ? '退货已执行' : '已驳回')
  loadReturns()
}

// 报表下钻深链（BR-RPT-06）：?orderNo=PO... 自动筛选并打开该单据详情
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

onMounted(() => {
  bootstrap()
  loadSuppliers()
})
</script>

<template>
  <div class="page">
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- ==================== 采购单列表（FR-PUR-03） ==================== -->
      <el-tab-pane label="采购单" name="orders">
        <el-card shadow="never">
          <el-form inline @submit.prevent="search">
            <el-form-item label="单号">
              <el-input v-model="query.orderNo" placeholder="PO 单号" clearable style="width: 160px" @keyup.enter="search" />
            </el-form-item>
            <el-form-item label="供应商">
              <el-select v-model="query.supplierId" placeholder="全部" clearable filterable style="width: 160px">
                <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="query.status" placeholder="全部" clearable style="width: 140px">
                <el-option v-for="(v, k) in PURCHASE_STATUS_MAP" :key="k" :label="v.label" :value="k" />
              </el-select>
            </el-form-item>
            <el-form-item label="创建人">
              <el-input v-model="query.createBy" placeholder="创建人" clearable style="width: 110px" @keyup.enter="search" />
            </el-form-item>
            <el-form-item label="创建日期">
              <el-date-picker
                v-model="createDateRange"
                type="daterange"
                value-format="YYYY-MM-DD"
                start-placeholder="开始"
                end-placeholder="结束"
                style="width: 240px"
              />
            </el-form-item>
            <el-form-item label="商品">
              <el-input v-model="query.goodsKeyword" placeholder="商品名称/编码" clearable style="width: 150px" @keyup.enter="search" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="search">查询</el-button>
              <el-button @click="resetQuery">重置</el-button>
              <el-button v-if="auth.hasPerm('sys:pur:create')" type="success" @click="openCreate">新建采购单</el-button>
            </el-form-item>
          </el-form>

          <el-table v-loading="loading" :data="list" stripe>
            <el-table-column prop="orderNo" label="单号" width="155" fixed="left" />
            <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
            <el-table-column label="状态" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status).type" size="small">{{ statusTag(row.status).label }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="创建" width="150">
              <template #default="{ row }">{{ row.createBy }}<br /><span class="sub">{{ fmtTime(row.createTime) }}</span></template>
            </el-table-column>
            <el-table-column prop="expectDate" label="预计到货" width="105" />
            <el-table-column label="总额" width="100" align="right">
              <template #default="{ row }">￥{{ money(row.totalAmount) }}</template>
            </el-table-column>
            <el-table-column label="入库进度" width="130">
              <template #default="{ row }">
                <el-progress
                  :percentage="Math.min(row.receiveProgress ?? 0, 100)"
                  :stroke-width="14"
                  :text-inside="true"
                  :status="(row.receiveProgress ?? 0) >= 100 ? 'success' : undefined"
                />
              </template>
            </el-table-column>
            <el-table-column label="结算" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="row.settleStatus === 'SETTLED' ? 'success' : row.settleStatus === 'PARTIAL' ? 'warning' : 'info'" size="small">
                  {{ SETTLE_STATUS_MAP[row.settleStatus] ?? row.settleStatus }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="270" fixed="right" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDetail(row as PurchaseOrderVO)">详情</el-button>
                <el-button v-if="row.status === 'DRAFT' && auth.hasPerm('sys:pur:update')" link type="primary" @click="openEdit(row as PurchaseOrderVO)">编辑</el-button>
                <el-button v-if="row.status === 'DRAFT' && auth.hasPerm('sys:pur:submit')" link type="success" @click="submitOrder(row as PurchaseOrderVO)">提交</el-button>
                <el-button
                  v-if="row.status === 'SUBMITTED' && row.createBy === auth.user?.realName && auth.hasPerm('sys:pur:submit')"
                  link
                  type="warning"
                  @click="withdrawOrder(row as PurchaseOrderVO)"
                >撤回</el-button>
                <el-button v-if="row.status === 'SUBMITTED' && auth.hasPerm('sys:pur:audit')" link type="primary" @click="openAudit(row as PurchaseOrderVO)">审核</el-button>
                <el-button v-if="row.status === 'DRAFT' && auth.hasPerm('sys:pur:terminate')" link type="danger" @click="cancelOrder(row as PurchaseOrderVO)">作废</el-button>
                <el-button v-if="row.status === 'APPROVED' && auth.hasPerm('sys:pur:terminate')" link type="danger" @click="terminateOrder(row as PurchaseOrderVO)">终止</el-button>
                <el-button v-if="row.status === 'PARTIAL' && auth.hasPerm('sys:pur:terminate')" link type="warning" @click="closeOrder(row as PurchaseOrderVO)">关闭短少</el-button>
                <el-button v-if="returnable(row as PurchaseOrderVO) && auth.hasPerm('sys:pur:return')" link type="danger" @click="openReturn(row as PurchaseOrderVO)">退货</el-button>
                <el-button v-if="settlable(row as PurchaseOrderVO) && auth.hasPerm('sys:pur:settle')" link type="success" @click="openSettle(row as PurchaseOrderVO)">结算</el-button>
                <el-button v-if="row.status === 'DRAFT' && auth.hasPerm('sys:pur:delete')" link type="danger" @click="removeOrder(row as PurchaseOrderVO)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-row">
            <el-pagination
              v-model:current-page="query.page"
              v-model:page-size="query.size"
              :total="total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next, jumper"
              @current-change="load"
              @size-change="search"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ==================== 采购退货单（FR-PUR-08） ==================== -->
      <el-tab-pane label="采购退货" name="returns">
        <el-card shadow="never">
          <el-form inline @submit.prevent="searchReturns">
            <el-form-item label="退货单号">
              <el-input v-model="returnQuery.orderNo" placeholder="PT 单号" clearable style="width: 160px" @keyup.enter="searchReturns" />
            </el-form-item>
            <el-form-item label="采购单号">
              <el-input v-model="returnQuery.purchaseOrderNo" placeholder="PO 单号" clearable style="width: 160px" @keyup.enter="searchReturns" />
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="returnQuery.status" placeholder="全部" clearable style="width: 130px">
                <el-option v-for="(v, k) in RETURN_STATUS_MAP" :key="k" :label="v.label" :value="k" />
              </el-select>
            </el-form-item>
            <el-form-item label="日期">
              <el-date-picker
                v-model="returnDateRange"
                type="daterange"
                value-format="YYYY-MM-DD"
                start-placeholder="开始"
                end-placeholder="结束"
                style="width: 240px"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="searchReturns">查询</el-button>
            </el-form-item>
          </el-form>

          <el-table v-loading="returnTabLoading" :data="returnList" stripe>
            <el-table-column prop="orderNo" label="退货单号" width="155" fixed="left" />
            <el-table-column prop="purchaseOrderNo" label="原采购单" width="155" />
            <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
            <el-table-column label="状态" width="95" align="center">
              <template #default="{ row }">
                <el-tag :type="RETURN_STATUS_MAP[row.status]?.type ?? 'info'" size="small">
                  {{ RETURN_STATUS_MAP[row.status]?.label ?? row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="退货金额" width="110" align="right">
              <template #default="{ row }">￥{{ money(row.totalAmount) }}</template>
            </el-table-column>
            <el-table-column prop="returnReason" label="退货原因" min-width="140" show-overflow-tooltip />
            <el-table-column prop="createBy" label="创建人" width="90" />
            <el-table-column label="创建时间" width="150">
              <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="审核" width="130">
              <template #default="{ row }">{{ row.auditBy ?? '—' }}<br /><span class="sub">{{ fmtTime(row.auditTime) }}</span></template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openReturnDetail(row as PurchaseReturnVO)">详情</el-button>
                <el-button
                  v-if="row.status === 'SUBMITTED' && auth.hasPerm('sys:pur:return-audit')"
                  link
                  type="warning"
                  @click="auditReturn(row as PurchaseReturnVO)"
                >审核</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-row">
            <el-pagination
              v-model:current-page="returnQuery.page"
              v-model:page-size="returnQuery.size"
              :total="returnTotal"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next, jumper"
              @current-change="loadReturns"
              @size-change="searchReturns"
            />
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- ==================== 采购单详情 ==================== -->
    <el-drawer v-model="detailDrawer.visible" title="采购单详情" size="720px">
      <div v-loading="detailDrawer.loading">
        <template v-if="detailDrawer.data">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="单号">{{ detailDrawer.data.orderNo }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="statusTag(detailDrawer.data.status).type" size="small">{{ statusTag(detailDrawer.data.status).label }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="供应商">{{ detailDrawer.data.supplierName }}</el-descriptions-item>
            <el-descriptions-item label="预计到货">{{ detailDrawer.data.expectDate ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="创建">{{ detailDrawer.data.createBy }} / {{ fmtTime(detailDrawer.data.createTime) }}</el-descriptions-item>
            <el-descriptions-item label="提交">{{ fmtTime(detailDrawer.data.submitTime) }}</el-descriptions-item>
            <el-descriptions-item label="审核">{{ detailDrawer.data.auditBy ?? '—' }} / {{ fmtTime(detailDrawer.data.auditTime) }}</el-descriptions-item>
            <el-descriptions-item label="审核意见">{{ detailDrawer.data.auditRemark ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="订购总额">￥{{ money(detailDrawer.data.totalAmount) }}</el-descriptions-item>
            <el-descriptions-item label="实收金额">￥{{ money(detailDrawer.data.receivedAmount) }}</el-descriptions-item>
            <el-descriptions-item label="退货金额">￥{{ money(detailDrawer.data.returnAmount) }}</el-descriptions-item>
            <el-descriptions-item label="结算">
              {{ SETTLE_STATUS_MAP[detailDrawer.data.settleStatus] ?? detailDrawer.data.settleStatus }}
              <template v-if="detailDrawer.data.settleStatus !== 'UNSETTLED'">
                （￥{{ money(detailDrawer.data.settleAmount) }} {{ SETTLE_METHOD_MAP[detailDrawer.data.settleMethod ?? ''] ?? '' }} {{ detailDrawer.data.settleDate ?? '' }} 经办：{{ detailDrawer.data.settleBy ?? '—' }}）
              </template>
            </el-descriptions-item>
            <el-descriptions-item v-if="detailDrawer.data.terminateReason" label="终止/作废原因" :span="2">
              {{ detailDrawer.data.terminateReason }}
            </el-descriptions-item>
            <el-descriptions-item v-if="detailDrawer.data.remark" label="备注" :span="2">{{ detailDrawer.data.remark }}</el-descriptions-item>
          </el-descriptions>

          <el-table :data="detailDrawer.data.items" stripe size="small" style="margin-top: 12px">
            <el-table-column prop="goodsCode" label="编码" width="100" />
            <el-table-column prop="goodsName" label="商品" min-width="140" show-overflow-tooltip />
            <el-table-column prop="unitName" label="单位" width="60" />
            <el-table-column label="订购" width="80" align="right" prop="quantity" />
            <el-table-column label="单价" width="80" align="right">
              <template #default="{ row }">{{ money(row.price) }}</template>
            </el-table-column>
            <el-table-column label="金额" width="90" align="right">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="已入" width="70" align="right" prop="receivedQty" />
            <el-table-column label="已退" width="70" align="right" prop="returnedQty" />
            <el-table-column label="待入库" width="70" align="right" prop="pendingQty" />
          </el-table>
        </template>
      </div>
      <template #footer>
        <el-button v-if="detailDrawer.data" @click="printOrder">打印（A4）</el-button>
      </template>
    </el-drawer>

    <!-- ==================== 新建 / 编辑采购单（FR-PUR-01） ==================== -->
    <el-dialog
      v-model="editDialog.visible"
      :title="editDialog.editing ? '编辑采购单（草稿）' : '新建采购单'"
      width="860px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="明细金额与总额自动计算（BR-PUR-03）；保存为草稿后可反复编辑，提交后由店长审核"
        class="dialog-alert"
      />
      <el-form label-width="90px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="供应商" required>
              <el-select v-model="editDialog.supplierId" placeholder="选择供应商" filterable style="width: 100%">
                <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="预计到货">
              <el-date-picker v-model="editDialog.expectDate" type="date" value-format="YYYY-MM-DD" placeholder="选填" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="备注">
              <el-input v-model="editDialog.remark" maxlength="200" placeholder="选填" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-table :data="editDialog.items" size="small" stripe>
        <el-table-column prop="goodsCode" label="编码" width="95" />
        <el-table-column prop="goodsName" label="商品" min-width="130" show-overflow-tooltip />
        <el-table-column prop="unitName" label="单位" width="55" />
        <el-table-column label="数量" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="0.001" :precision="3" :controls="false" style="width: 110px" />
          </template>
        </el-table-column>
        <el-table-column label="单价" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.price" :min="0" :precision="2" :controls="false" style="width: 110px" />
          </template>
        </el-table-column>
        <el-table-column label="金额" width="90" align="right">
          <template #default="{ row }">￥{{ lineAmount(row as EditItem).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeItem($index)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="items-footer">
        <el-button type="primary" plain @click="openGoodsPicker">+ 添加商品</el-button>
        <span>合计：￥{{ totalAmount.toFixed(2) }}</span>
      </div>

      <template #footer>
        <el-button @click="editDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="editDialog.saving" @click="saveOrder">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- 商品选择 -->
    <el-dialog v-model="goodsPicker.visible" title="选择商品（仅启用商品可采购）" width="620px">
      <el-form inline @submit.prevent="searchGoods">
        <el-form-item>
          <el-input v-model="goodsPicker.keyword" placeholder="商品名称/编码" clearable style="width: 240px" @keyup.enter="searchGoods" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="searchGoods">搜索</el-button>
        </el-form-item>
      </el-form>
      <el-table v-loading="goodsPicker.loading" :data="goodsPicker.list" size="small" stripe max-height="360">
        <el-table-column prop="goodsCode" label="编码" width="100" />
        <el-table-column prop="name" label="商品" min-width="150" show-overflow-tooltip />
        <el-table-column prop="spec" label="规格" width="90" show-overflow-tooltip />
        <el-table-column prop="unitName" label="单位" width="60" />
        <el-table-column label="进货参考价" width="100" align="right">
          <template #default="{ row }">{{ money(row.purchasePrice) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="pickGoods(row as GoodsListVO)">选择</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- ==================== 审核（FR-PUR-04） ==================== -->
    <el-dialog v-model="auditDialog.visible" :title="`审核 ${auditDialog.orderNo}`" width="440px">
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="审核结论">
          <el-radio-group v-model="auditDialog.approved">
            <el-radio :value="true">通过（转已审核待入库）</el-radio>
            <el-radio :value="false">驳回（退回草稿）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="auditDialog.approved ? '意见' : '驳回意见'" :required="!auditDialog.approved">
          <el-input
            v-model="auditDialog.remark"
            type="textarea"
            :rows="3"
            maxlength="200"
            :placeholder="auditDialog.approved ? '选填' : '驳回时必填（FR-PUR-04）'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="auditDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="auditDialog.saving" @click="saveAudit">提交</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 结算登记（FR-PUR-09） ==================== -->
    <el-dialog v-model="settleDialog.visible" :title="`结算登记 ${settleDialog.orderNo}`" width="460px">
      <el-alert type="info" :closable="false" show-icon :title="`订购 ￥${settleDialog.totalAmount.toFixed(2)}，实收 ￥${settleDialog.receivedAmount.toFixed(2)}，退货 ￥${settleDialog.returnAmount.toFixed(2)}；结算登记仅做标记，不做财务核销`" class="dialog-alert" />
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="结算状态" required>
          <el-radio-group v-model="settleDialog.form.settleStatus">
            <el-radio value="SETTLED">已结算</el-radio>
            <el-radio value="PARTIAL">部分结算</el-radio>
            <el-radio value="UNSETTLED">未结算</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="settleDialog.form.settleStatus !== 'UNSETTLED'">
          <el-form-item label="结算金额" required>
            <el-input-number v-model="settleDialog.form.settleAmount" :min="0" :precision="2" :controls="false" style="width: 180px" />
          </el-form-item>
          <el-form-item label="结算方式" required>
            <el-select v-model="settleDialog.form.settleMethod" style="width: 180px">
              <el-option v-for="(label, m) in SETTLE_METHOD_MAP" :key="m" :label="label" :value="m" />
            </el-select>
          </el-form-item>
          <el-form-item label="结算日期">
            <el-date-picker v-model="settleDialog.form.settleDate" type="date" value-format="YYYY-MM-DD" style="width: 180px" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="settleDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="settleDialog.saving" @click="saveSettle">保存</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 创建退货（FR-PUR-08） ==================== -->
    <el-dialog v-model="returnDialog.visible" :title="`采购退货 ${returnDialog.purchaseOrderNo}`" width="720px">
      <el-alert type="warning" :closable="false" show-icon title="退货数量不可超过可退数量（已入库 − 已退）；退货单价按入库时成本单价计；创建后须店长审核通过才执行退货" class="dialog-alert" />
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="退货原因" required>
          <el-input v-model="returnDialog.returnReason" maxlength="200" placeholder="如：临期质量问题、包装破损" />
        </el-form-item>
        <el-table :data="returnDialog.items" size="small" stripe>
          <el-table-column prop="goodsName" label="商品" min-width="170" show-overflow-tooltip />
          <el-table-column prop="unitName" label="单位" width="55" />
          <el-table-column label="可退数量" width="90" align="right" prop="returnable" />
          <el-table-column label="退货数量" width="140">
            <template #default="{ row }">
              <el-input-number v-model="row.returnQty" :min="0" :max="row.returnable" :precision="3" :controls="false" style="width: 120px" />
            </template>
          </el-table-column>
        </el-table>
        <el-form-item label="备注" style="margin-top: 10px">
          <el-input v-model="returnDialog.remark" maxlength="200" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="returnDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="returnDialog.saving" @click="saveReturn">提交退货单</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 退货单详情 ==================== -->
    <el-drawer v-model="returnDrawer.visible" title="退货单详情" size="640px">
      <div v-loading="returnDrawer.loading">
        <template v-if="returnDrawer.data">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="退货单号">{{ returnDrawer.data.orderNo }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="RETURN_STATUS_MAP[returnDrawer.data.status]?.type ?? 'info'" size="small">
                {{ RETURN_STATUS_MAP[returnDrawer.data.status]?.label ?? returnDrawer.data.status }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="原采购单">{{ returnDrawer.data.purchaseOrderNo }}</el-descriptions-item>
            <el-descriptions-item label="供应商">{{ returnDrawer.data.supplierName }}</el-descriptions-item>
            <el-descriptions-item label="退货金额">￥{{ money(returnDrawer.data.totalAmount) }}</el-descriptions-item>
            <el-descriptions-item label="创建">{{ returnDrawer.data.createBy }} / {{ fmtTime(returnDrawer.data.createTime) }}</el-descriptions-item>
            <el-descriptions-item label="审核">{{ returnDrawer.data.auditBy ?? '—' }} / {{ fmtTime(returnDrawer.data.auditTime) }}</el-descriptions-item>
            <el-descriptions-item label="退货原因" :span="2">{{ returnDrawer.data.returnReason }}</el-descriptions-item>
            <el-descriptions-item v-if="returnDrawer.data.auditRemark" label="审核意见" :span="2">{{ returnDrawer.data.auditRemark }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="returnDrawer.data.items" stripe size="small" style="margin-top: 12px">
            <el-table-column prop="goodsCode" label="编码" width="100" />
            <el-table-column prop="goodsName" label="商品" min-width="140" show-overflow-tooltip />
            <el-table-column prop="unitName" label="单位" width="60" />
            <el-table-column label="退货数量" width="90" align="right" prop="returnQty" />
            <el-table-column label="退货单价" width="90" align="right">
              <template #default="{ row }">{{ money(row.returnPrice) }}</template>
            </el-table-column>
            <el-table-column label="金额" width="90" align="right">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.dialog-alert {
  margin-bottom: 12px;
}

.items-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
  font-weight: 600;
}
</style>

<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm, prompt } from '@/utils/confirm'
import { moneyText } from '@/utils/format'
import { computed, onMounted, reactive, ref } from 'vue'
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
import { pageSuppliers, getSupplierGoods, type SupplierListVO } from '@/api/supplier'
import { pageGoods } from '@/api/goods'
import { getDictItems } from '@/api/system'
import { pMoney, pQty, printSheet } from '@/utils/print'

const auth = useAuthStore()
const route = useRoute()

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
}

function statusTag(status: string) {
  return PURCHASE_STATUS_MAP[status] ?? { label: status, type: 'info' as const }
}

/** Element 标签类型 → Nuxt UI 徽标颜色 */
function tagColor(type?: 'success' | 'warning' | 'danger' | 'info' | 'primary') {
  if (type === 'success') return 'success' as const
  if (type === 'warning') return 'warning' as const
  if (type === 'danger') return 'error' as const
  if (type === 'primary') return 'primary' as const
  return 'neutral' as const
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
  /** 建议采购原值（建议采购生成时记录，UC-INV-002 6a；手工添加为空） */
  suggestedQty?: number | null
  suggestedPrice?: number | null
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
    suggestedQty: i.suggestedQty,
    suggestedPrice: i.suggestedPrice,
    remark: i.remark ?? '',
  }))
  editDialog.visible = true
}

/** 商品选择行：全量商品档案 / 供应商供货商品两种来源统一形状（FR-SUP-04） */
interface PickerGoodsRow {
  id: number
  goodsCode: string
  name: string
  spec: string | null
  unitName: string | null
  purchasePrice: number | null
  supplyPrice?: number | null
  lastSupplyPrice?: number | null
}

/** 商品搜索弹窗：默认按所选供应商的供货商品过滤（带出最近供货价），可切换全量商品（FR-PUR-01） */
const goodsPicker = reactive({
  visible: false,
  keyword: '',
  loading: false,
  /** 仅看该供应商供货商品（FR-SUP-04；未选供应商时禁用） */
  onlySupplier: true,
  list: [] as PickerGoodsRow[],
})

function openGoodsPicker() {
  goodsPicker.keyword = ''
  goodsPicker.onlySupplier = editDialog.supplierId != null
  goodsPicker.visible = true
  searchGoods()
}

async function searchGoods() {
  goodsPicker.loading = true
  try {
    if (goodsPicker.onlySupplier && editDialog.supplierId != null) {
      // 供应商供货商品（FR-SUP-04）：前端按关键字过滤，仅启用商品可采购（BR-PUR-03）
      const all = await getSupplierGoods(editDialog.supplierId)
      const kw = goodsPicker.keyword.trim().toLowerCase()
      goodsPicker.list = all
        .filter((g) => g.goodsStatus !== 0)
        .filter(
          (g) =>
            !kw ||
            g.goodsName.toLowerCase().includes(kw) ||
            g.goodsCode.toLowerCase().includes(kw),
        )
        .map((g) => ({
          id: g.goodsId,
          goodsCode: g.goodsCode,
          name: g.goodsName,
          spec: g.spec,
          unitName: g.unitName,
          purchasePrice: null,
          supplyPrice: g.supplyPrice,
          lastSupplyPrice: g.lastSupplyPrice,
        }))
      return
    }
    const data = await pageGoods({
      name: goodsPicker.keyword.trim() || undefined,
      goodsCode: goodsPicker.keyword.trim() || undefined,
      status: 1,
      page: 1,
      size: 50,
    })
    goodsPicker.list = data.list.map((g) => ({
      id: g.id,
      goodsCode: g.goodsCode,
      name: g.name,
      spec: g.spec,
      unitName: g.unitName,
      purchasePrice: g.purchasePrice,
    }))
  } finally {
    goodsPicker.loading = false
  }
}

function pickGoods(goods: PickerGoodsRow) {
  if (editDialog.items.some((i) => i.goodsId === goods.id)) {
    notify.warning('该商品已在明细中（同商品仅一行，BR-PUR-03）')
    return
  }
  editDialog.items.push({
    goodsId: goods.id,
    goodsCode: goods.goodsCode,
    goodsName: goods.name,
    unitName: goods.unitName ?? '',
    quantity: 1,
    // 单价默认链（FR-SUP-04）：该供应商最近供货价 → 协议供货价 → 商品档案进货参考价
    price: goods.lastSupplyPrice ?? goods.supplyPrice ?? goods.purchasePrice ?? 0,
    suggestedQty: null,
    suggestedPrice: null,
    remark: '',
  })
  goodsPicker.visible = false
}

function removeItem(index: number) {
  editDialog.items.splice(index, 1)
}

async function saveOrder() {
  if (editDialog.supplierId == null) {
    notify.warning('请选择供应商')
    return
  }
  if (editDialog.items.length === 0) {
    notify.warning('请至少添加一条采购明细（BR-PUR-03）')
    return
  }
  if (editDialog.items.some((i) => !i.quantity || i.quantity <= 0 || i.price == null || i.price < 0)) {
    notify.warning('明细数量必须大于 0、单价不能为负（BR-PUR-03）')
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
        suggestedQty: i.suggestedQty ?? undefined,
        suggestedPrice: i.suggestedPrice ?? undefined,
        remark: i.remark || undefined,
      })),
    }
    if (editDialog.editing) {
      await updatePurchaseOrder(editDialog.id, payload)
      notify.success('采购单已保存')
    } else {
      await createPurchaseOrder(payload)
      notify.success('采购单草稿已创建')
    }
    editDialog.visible = false
    load()
  } finally {
    editDialog.saving = false
  }
}

// ==================== 提交 / 撤回 / 审核（FR-PUR-04） ====================
async function submitOrder(row: PurchaseOrderVO) {
  await confirm(`确认提交采购单 ${row.orderNo} 审核？提交后由店长审核`, '提交审核', { type: 'info' })
  await submitPurchaseOrder(row.id)
  notify.success('已提交审核')
  load()
}

async function withdrawOrder(row: PurchaseOrderVO) {
  await confirm(`确认撤回采购单 ${row.orderNo}？撤回后回到草稿可继续编辑`, '撤回', { type: 'warning' })
  await withdrawPurchaseOrder(row.id)
  notify.success('已撤回')
  load()
}

/** 审核弹窗：通过 / 驳回（驳回必填意见，BR-PUR-02）；密码二次确认（BR-SYS-06） */
const auditDialog = reactive({
  visible: false,
  saving: false,
  id: 0,
  orderNo: '',
  approved: true,
  remark: '',
  password: '',
})

function openAudit(row: PurchaseOrderVO) {
  auditDialog.id = row.id
  auditDialog.orderNo = row.orderNo
  auditDialog.approved = true
  auditDialog.remark = ''
  auditDialog.password = ''
  auditDialog.visible = true
}

async function saveAudit() {
  if (!auditDialog.approved && !auditDialog.remark.trim()) {
    notify.warning('驳回时必须填写审核意见（FR-PUR-04）')
    return
  }
  if (!auditDialog.password) {
    notify.warning('请输入当前账号密码以确认操作')
    return
  }
  auditDialog.saving = true
  try {
    await auditPurchaseOrder(auditDialog.id, auditDialog.approved,
      auditDialog.remark.trim() || undefined, auditDialog.password)
    notify.success(auditDialog.approved ? '审核通过' : '已驳回退回草稿')
    auditDialog.visible = false
    auditDialog.password = ''
    load()
  } finally {
    auditDialog.saving = false
  }
}

// ==================== 作废 / 终止 / 关闭（FR-PUR-05） ====================
async function cancelOrder(row: PurchaseOrderVO) {
  const { value } = await prompt('作废后单据保留可查但不可再流转，请填写作废原因', `作废 ${row.orderNo}`, {
    inputPlaceholder: '作废原因（必填）',
    inputValidator: (v: string) => (v && v.trim() ? true : '原因不能为空'),
  })
  await cancelPurchaseOrder(row.id, value.trim())
  notify.success('已作废')
  load()
}

async function terminateOrder(row: PurchaseOrderVO) {
  const { value } = await prompt('终止已审核未入库的采购单，在途量将同步扣除，请填写终止原因', `终止 ${row.orderNo}`, {
    inputPlaceholder: '终止原因（必填）',
    inputValidator: (v: string) => (v && v.trim() ? true : '原因不能为空'),
  })
  await terminatePurchaseOrder(row.id, value.trim())
  notify.success('已终止')
  load()
}

async function closeOrder(row: PurchaseOrderVO) {
  const { value } = await prompt('关闭后剩余数量不再入库，单据转已完成，请填写短少原因', `关闭短少 ${row.orderNo}`, {
    inputPlaceholder: '短少原因（必填）',
    inputValidator: (v: string) => (v && v.trim() ? true : '原因不能为空'),
  })
  await closePurchaseOrder(row.id, value.trim())
  notify.success('已关闭剩余数量')
  load()
}

async function removeOrder(row: PurchaseOrderVO) {
  await confirm(`确认删除草稿 ${row.orderNo}？删除后不可恢复`, '删除草稿', { type: 'warning' })
  await deletePurchaseOrder(row.id)
  notify.success('已删除')
  load()
}

// ==================== 结算登记（FR-PUR-09） ====================
/** 结算方式选项（FR-SYS-07/GAP-P1-09：取自数据字典 settle_type 启用项，字典缺失时回退内置展示） */
const settleTypeOptions = ref<{ label: string; value: string }[]>(
  Object.entries(SETTLE_METHOD_MAP).map(([value, label]) => ({ label, value })),
)
const settleTypeLabels = ref<Record<string, string>>({ ...SETTLE_METHOD_MAP })

async function loadSettleTypes() {
  try {
    const items = await getDictItems('settle_type')
    if (items.length) {
      settleTypeOptions.value = items.map((i) => ({ label: i.label, value: i.value }))
      settleTypeLabels.value = Object.fromEntries(items.map((i) => [i.value, i.label]))
    }
  } catch {
    // 回退内置展示，后端保存时仍按字典校验
  }
}

function settleLabel(code: string | null | undefined) {
  if (!code) return ''
  return settleTypeLabels.value[code] ?? code
}

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
    notify.warning('请填写结算金额')
    return
  }
  if (settleDialog.form.settleStatus !== 'UNSETTLED' && !settleDialog.form.settleMethod) {
    notify.warning('请选择结算方式（数据字典 settle_type）')
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
    notify.success('结算登记完成')
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
    notify.warning('该采购单没有可退商品（可退 = 已入库 − 已退）')
    return
  }
  returnDialog.visible = true
}

async function saveReturn() {
  const selected = returnDialog.items.filter((i) => i.returnQty != null && i.returnQty > 0)
  if (selected.length === 0) {
    notify.warning('请填写退货数量')
    return
  }
  if (selected.some((i) => i.returnQty! > i.returnable)) {
    notify.warning('退货数量不能超过可退数量（已入库 − 已退）')
    return
  }
  if (!returnDialog.returnReason.trim()) {
    notify.warning('请填写退货原因')
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
    notify.success('退货单已创建，待店长审核')
    returnDialog.visible = false
    load()
  } finally {
    returnDialog.saving = false
  }
}

/** 退货审核：通过执行退货 / 驳回须意见；密码二次确认（BR-SYS-06） */
async function auditReturn(row: PurchaseReturnVO) {
  const { value } = await prompt(
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
    notify.warning('驳回时必须填写审核意见')
    return
  }
  const { value: password } = await prompt(
    '该操作将按退货单执行库存冲减，请输入当前账号密码确认：',
    '敏感操作二次确认',
    {
      inputType: 'password',
      inputPlaceholder: '当前账号密码',
      inputValidator: (v: string) => (v ? true : '密码不能为空'),
    },
  )
  await auditPurchaseReturn(row.id, approved, approved ? undefined : value.trim(), password)
  notify.success(approved ? '退货已执行' : '已驳回')
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
  loadSettleTypes()
})
</script>

<template>
  <div class="page">
    <AppTabs v-model="activeTab" @tab-change="onTabChange">
      <!-- ==================== 采购单列表（FR-PUR-03） ==================== -->
      <AppTabPane label="采购单" name="orders">
        <UCard>
          <AppForm inline @submit.prevent="search">
            <AppFormItem label="单号">
              <AppInput v-model="query.orderNo" placeholder="PO 单号" clearable style="width: 160px" @keyup.enter="search" />
            </AppFormItem>
            <AppFormItem label="供应商">
              <AppSelect v-model="query.supplierId" placeholder="全部" clearable filterable style="width: 160px">
                <AppOption v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
              </AppSelect>
            </AppFormItem>
            <AppFormItem label="状态">
              <AppSelect v-model="query.status" placeholder="全部" clearable style="width: 140px">
                <AppOption v-for="(v, k) in PURCHASE_STATUS_MAP" :key="k" :label="v.label" :value="k" />
              </AppSelect>
            </AppFormItem>
            <AppFormItem label="创建人">
              <AppInput v-model="query.createBy" placeholder="创建人" clearable style="width: 110px" @keyup.enter="search" />
            </AppFormItem>
            <AppFormItem label="创建日期">
              <AppDatePicker
                v-model="createDateRange"
                type="daterange"
                value-format="YYYY-MM-DD"
                start-placeholder="开始"
                end-placeholder="结束"
                style="width: 240px"
              />
            </AppFormItem>
            <AppFormItem label="商品">
              <AppInput v-model="query.goodsKeyword" placeholder="商品名称/编码" clearable style="width: 150px" @keyup.enter="search" />
            </AppFormItem>
            <AppFormItem>
              <div class="flex flex-wrap gap-2">
                <UButton label="查询" @click="search" />
                <UButton color="neutral" variant="outline" label="重置" @click="resetQuery" />
                <UButton
                  v-if="auth.hasPerm('sys:pur:create')"
                  color="success"
                  label="新建采购单"
                  @click="openCreate"
                />
              </div>
            </AppFormItem>
          </AppForm>

          <AppTable :loading="loading" border :data="list" stripe>
            <AppTableColumn prop="orderNo" label="单号" width="155" fixed="left" />
            <AppTableColumn prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
            <AppTableColumn label="状态" width="110" align="center">
              <template #default="{ row }">
                <UBadge :color="tagColor(statusTag(row.status).type)" variant="soft" size="sm">
                  {{ statusTag(row.status).label }}
                </UBadge>
              </template>
            </AppTableColumn>
            <AppTableColumn label="创建" width="150">
              <template #default="{ row }">{{ row.createBy }}<br /><span class="sub">{{ fmtTime(row.createTime) }}</span></template>
            </AppTableColumn>
            <AppTableColumn prop="expectDate" label="预计到货" width="105" />
            <AppTableColumn label="总额" width="100" align="right">
              <template #default="{ row }">￥{{ money(row.totalAmount) }}</template>
            </AppTableColumn>
            <AppTableColumn label="入库进度" width="130">
              <template #default="{ row }">
                <div class="progress-cell">
                  <div class="progress-bar">
                    <UProgress
                      :model-value="Math.min(row.receiveProgress ?? 0, 100)"
                      :max="100"
                      :color="(row.receiveProgress ?? 0) >= 100 ? 'success' : 'primary'"
                    />
                  </div>
                  <span class="progress-text">{{ Math.min(row.receiveProgress ?? 0, 100) }}%</span>
                </div>
              </template>
            </AppTableColumn>
            <AppTableColumn label="结算" width="90" align="center">
              <template #default="{ row }">
                <UBadge
                  :color="row.settleStatus === 'SETTLED' ? 'success' : row.settleStatus === 'PARTIAL' ? 'warning' : 'neutral'"
                  variant="soft"
                  size="sm"
                >
                  {{ SETTLE_STATUS_MAP[row.settleStatus] ?? row.settleStatus }}
                </UBadge>
              </template>
            </AppTableColumn>
            <AppTableColumn label="操作" width="270" fixed="right" align="center">
              <template #default="{ row }">
                <div class="flex flex-wrap items-center justify-center gap-x-1">
                  <UButton variant="link" size="sm" label="详情" @click="openDetail(row as PurchaseOrderVO)" />
                  <UButton v-if="row.status === 'DRAFT' && auth.hasPerm('sys:pur:update')" variant="link" size="sm" label="编辑" @click="openEdit(row as PurchaseOrderVO)" />
                  <UButton v-if="row.status === 'DRAFT' && auth.hasPerm('sys:pur:submit')" variant="link" size="sm" color="success" label="提交" @click="submitOrder(row as PurchaseOrderVO)" />
                  <UButton
                    v-if="row.status === 'SUBMITTED' && row.createBy === auth.user?.realName && auth.hasPerm('sys:pur:submit')"
                    variant="link"
                    size="sm"
                    color="warning"
                    label="撤回"
                    @click="withdrawOrder(row as PurchaseOrderVO)"
                  />
                  <UButton v-if="row.status === 'SUBMITTED' && auth.hasPerm('sys:pur:audit')" variant="link" size="sm" label="审核" @click="openAudit(row as PurchaseOrderVO)" />
                  <UButton v-if="row.status === 'DRAFT' && auth.hasPerm('sys:pur:terminate')" variant="link" size="sm" color="error" label="作废" @click="cancelOrder(row as PurchaseOrderVO)" />
                  <UButton v-if="row.status === 'APPROVED' && auth.hasPerm('sys:pur:terminate')" variant="link" size="sm" color="error" label="终止" @click="terminateOrder(row as PurchaseOrderVO)" />
                  <UButton v-if="row.status === 'PARTIAL' && auth.hasPerm('sys:pur:terminate')" variant="link" size="sm" color="warning" label="关闭短少" @click="closeOrder(row as PurchaseOrderVO)" />
                  <UButton v-if="returnable(row as PurchaseOrderVO) && auth.hasPerm('sys:pur:return')" variant="link" size="sm" color="error" label="退货" @click="openReturn(row as PurchaseOrderVO)" />
                  <UButton v-if="settlable(row as PurchaseOrderVO) && auth.hasPerm('sys:pur:settle')" variant="link" size="sm" color="success" label="结算" @click="openSettle(row as PurchaseOrderVO)" />
                  <UButton v-if="row.status === 'DRAFT' && auth.hasPerm('sys:pur:delete')" variant="link" size="sm" color="error" label="删除" @click="removeOrder(row as PurchaseOrderVO)" />
                </div>
              </template>
            </AppTableColumn>
          </AppTable>

          <div class="pagination-row">
            <AppPagination
              v-model:current-page="query.page"
              v-model:page-size="query.size"
              :total="total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next, jumper"
              @current-change="load"
              @size-change="search"
            />
          </div>
        </UCard>
      </AppTabPane>

      <!-- ==================== 采购退货单（FR-PUR-08） ==================== -->
      <AppTabPane label="采购退货" name="returns">
        <UCard>
          <AppForm inline @submit.prevent="searchReturns">
            <AppFormItem label="退货单号">
              <AppInput v-model="returnQuery.orderNo" placeholder="PT 单号" clearable style="width: 160px" @keyup.enter="searchReturns" />
            </AppFormItem>
            <AppFormItem label="采购单号">
              <AppInput v-model="returnQuery.purchaseOrderNo" placeholder="PO 单号" clearable style="width: 160px" @keyup.enter="searchReturns" />
            </AppFormItem>
            <AppFormItem label="状态">
              <AppSelect v-model="returnQuery.status" placeholder="全部" clearable style="width: 130px">
                <AppOption v-for="(v, k) in RETURN_STATUS_MAP" :key="k" :label="v.label" :value="k" />
              </AppSelect>
            </AppFormItem>
            <AppFormItem label="日期">
              <AppDatePicker
                v-model="returnDateRange"
                type="daterange"
                value-format="YYYY-MM-DD"
                start-placeholder="开始"
                end-placeholder="结束"
                style="width: 240px"
              />
            </AppFormItem>
            <AppFormItem>
              <UButton label="查询" @click="searchReturns" />
            </AppFormItem>
          </AppForm>

          <AppTable :loading="returnTabLoading" :data="returnList" stripe>
            <AppTableColumn prop="orderNo" label="退货单号" width="155" fixed="left" />
            <AppTableColumn prop="purchaseOrderNo" label="原采购单" width="155" />
            <AppTableColumn prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
            <AppTableColumn label="状态" width="95" align="center">
              <template #default="{ row }">
                <UBadge :color="tagColor(RETURN_STATUS_MAP[row.status]?.type ?? 'info')" variant="soft" size="sm">
                  {{ RETURN_STATUS_MAP[row.status]?.label ?? row.status }}
                </UBadge>
              </template>
            </AppTableColumn>
            <AppTableColumn label="退货金额" width="110" align="right">
              <template #default="{ row }">￥{{ money(row.totalAmount) }}</template>
            </AppTableColumn>
            <AppTableColumn prop="returnReason" label="退货原因" min-width="140" show-overflow-tooltip />
            <AppTableColumn prop="createBy" label="创建人" width="90" />
            <AppTableColumn label="创建时间" width="150">
              <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
            </AppTableColumn>
            <AppTableColumn label="审核" width="130">
              <template #default="{ row }">{{ row.auditBy ?? '—' }}<br /><span class="sub">{{ fmtTime(row.auditTime) }}</span></template>
            </AppTableColumn>
            <AppTableColumn label="操作" width="140" fixed="right" align="center">
              <template #default="{ row }">
                <div class="flex flex-wrap items-center justify-center gap-x-1">
                  <UButton variant="link" size="sm" label="详情" @click="openReturnDetail(row as PurchaseReturnVO)" />
                  <UButton
                    v-if="row.status === 'SUBMITTED' && auth.hasPerm('sys:pur:return-audit')"
                    variant="link"
                    size="sm"
                    color="warning"
                    label="审核"
                    @click="auditReturn(row as PurchaseReturnVO)"
                  />
                </div>
              </template>
            </AppTableColumn>
          </AppTable>

          <div class="pagination-row">
            <AppPagination
              v-model:current-page="returnQuery.page"
              v-model:page-size="returnQuery.size"
              :total="returnTotal"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next, jumper"
              @current-change="loadReturns"
              @size-change="searchReturns"
            />
          </div>
        </UCard>
      </AppTabPane>
    </AppTabs>

    <!-- ==================== 采购单详情 ==================== -->
    <AppDrawer v-model="detailDrawer.visible" title="采购单详情" size="720px">
      <div v-loading="detailDrawer.loading">
        <template v-if="detailDrawer.data">
          <AppDescriptions :column="2" border size="small">
            <AppDescriptionsItem label="单号">{{ detailDrawer.data.orderNo }}</AppDescriptionsItem>
            <AppDescriptionsItem label="状态">
              <UBadge :color="tagColor(statusTag(detailDrawer.data.status).type)" variant="soft" size="sm">
                {{ statusTag(detailDrawer.data.status).label }}
              </UBadge>
            </AppDescriptionsItem>
            <AppDescriptionsItem label="供应商">{{ detailDrawer.data.supplierName }}</AppDescriptionsItem>
            <AppDescriptionsItem label="预计到货">{{ detailDrawer.data.expectDate ?? '—' }}</AppDescriptionsItem>
            <AppDescriptionsItem label="创建">{{ detailDrawer.data.createBy }} / {{ fmtTime(detailDrawer.data.createTime) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="提交">{{ fmtTime(detailDrawer.data.submitTime) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="审核">{{ detailDrawer.data.auditBy ?? '—' }} / {{ fmtTime(detailDrawer.data.auditTime) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="审核意见">{{ detailDrawer.data.auditRemark ?? '—' }}</AppDescriptionsItem>
            <AppDescriptionsItem label="订购总额">￥{{ money(detailDrawer.data.totalAmount) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="实收金额">￥{{ money(detailDrawer.data.receivedAmount) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="退货金额">￥{{ money(detailDrawer.data.returnAmount) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="结算">
              {{ SETTLE_STATUS_MAP[detailDrawer.data.settleStatus] ?? detailDrawer.data.settleStatus }}
              <template v-if="detailDrawer.data.settleStatus !== 'UNSETTLED'">
                （￥{{ money(detailDrawer.data.settleAmount) }} {{ settleLabel(detailDrawer.data.settleMethod) }} {{ detailDrawer.data.settleDate ?? '' }} 经办：{{ detailDrawer.data.settleBy ?? '—' }}）
              </template>
            </AppDescriptionsItem>
            <AppDescriptionsItem v-if="detailDrawer.data.terminateReason" label="终止/作废原因" :span="2">
              {{ detailDrawer.data.terminateReason }}
            </AppDescriptionsItem>
            <AppDescriptionsItem v-if="detailDrawer.data.remark" label="备注" :span="2">{{ detailDrawer.data.remark }}</AppDescriptionsItem>
          </AppDescriptions>

          <AppTable :data="detailDrawer.data.items" stripe size="small" style="margin-top: 12px">
            <AppTableColumn prop="goodsCode" label="编码" width="100" />
            <AppTableColumn prop="goodsName" label="商品" min-width="140" show-overflow-tooltip />
            <AppTableColumn prop="unitName" label="单位" width="60" />
            <AppTableColumn label="订购" width="80" align="right" prop="quantity" />
            <AppTableColumn label="单价" width="80" align="right">
              <template #default="{ row }">{{ money(row.price) }}</template>
            </AppTableColumn>
            <AppTableColumn label="建议量" width="70" align="right">
              <template #default="{ row }">{{ row.suggestedQty ?? '—' }}</template>
            </AppTableColumn>
            <AppTableColumn label="建议价" width="80" align="right">
              <template #default="{ row }">{{ row.suggestedPrice != null ? money(row.suggestedPrice) : '—' }}</template>
            </AppTableColumn>
            <AppTableColumn label="金额" width="90" align="right">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </AppTableColumn>
            <AppTableColumn label="已入" width="70" align="right" prop="receivedQty" />
            <AppTableColumn label="已退" width="70" align="right" prop="returnedQty" />
            <AppTableColumn label="待入库" width="70" align="right" prop="pendingQty" />
          </AppTable>
        </template>
      </div>
      <template #footer>
        <UButton v-if="detailDrawer.data" color="neutral" variant="outline" label="打印（A4）" @click="printOrder" />
      </template>
    </AppDrawer>

    <!-- ==================== 新建 / 编辑采购单（FR-PUR-01） ==================== -->
    <AppModal
      v-model="editDialog.visible"
      :title="editDialog.editing ? '编辑采购单（草稿）' : '新建采购单'"
      width="860px"
      :close-on-click-modal="false"
    >
      <UAlert
        color="info"
        variant="soft"
        icon="i-lucide-info"
        title="明细金额与总额自动计算（BR-PUR-03）；保存为草稿后可反复编辑，提交后由店长审核"
        class="dialog-alert"
      />
      <AppForm label-width="90px" @submit.prevent>
        <AppRow :gutter="12">
          <AppCol :span="8">
            <AppFormItem label="供应商" required>
              <AppSelect v-model="editDialog.supplierId" placeholder="选择供应商" filterable style="width: 100%">
                <AppOption v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
              </AppSelect>
            </AppFormItem>
          </AppCol>
          <AppCol :span="8">
            <AppFormItem label="预计到货">
              <AppDatePicker v-model="editDialog.expectDate" type="date" value-format="YYYY-MM-DD" placeholder="选填" style="width: 100%" />
            </AppFormItem>
          </AppCol>
          <AppCol :span="8">
            <AppFormItem label="备注">
              <AppInput v-model="editDialog.remark" maxlength="200" placeholder="选填" />
            </AppFormItem>
          </AppCol>
        </AppRow>
      </AppForm>

      <AppTable :data="editDialog.items" size="small" stripe>
        <AppTableColumn prop="goodsCode" label="编码" width="95" />
        <AppTableColumn prop="goodsName" label="商品" min-width="130" show-overflow-tooltip />
        <AppTableColumn prop="unitName" label="单位" width="55" />
        <AppTableColumn label="数量" width="130">
          <template #default="{ row }">
            <AppInputNumber v-model="row.quantity" :min="0.001" :precision="3" :controls="false" style="width: 110px" />
          </template>
        </AppTableColumn>
        <AppTableColumn label="单价" width="130">
          <template #default="{ row }">
            <AppInputNumber v-model="row.price" :min="0" :precision="2" :controls="false" style="width: 110px" />
          </template>
        </AppTableColumn>
        <AppTableColumn label="建议量 / 建议价" width="120" align="center">
          <template #default="{ row }">
            <span v-if="row.suggestedQty != null || row.suggestedPrice != null" class="suggested-cell">
              {{ row.suggestedQty ?? '—' }} / {{ row.suggestedPrice != null ? money(row.suggestedPrice) : '—' }}
            </span>
            <span v-else>—</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="金额" width="90" align="right">
          <template #default="{ row }">￥{{ money(lineAmount(row as EditItem)) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="70" align="center">
          <template #default="{ $index }">
            <UButton variant="link" size="sm" color="error" label="移除" @click="removeItem($index)" />
          </template>
        </AppTableColumn>
      </AppTable>
      <div class="items-footer">
        <UButton variant="outline" label="+ 添加商品" @click="openGoodsPicker" />
        <span>合计：￥{{ money(totalAmount) }}</span>
      </div>

      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="editDialog.visible = false" />
        <UButton :loading="editDialog.saving" label="保存草稿" @click="saveOrder" />
      </template>
    </AppModal>

    <!-- 商品选择（FR-PUR-01：可按供应商供货商品过滤，亦可切换全量商品） -->
    <AppModal v-model="goodsPicker.visible" title="选择商品（仅启用商品可采购）" width="680px">
      <AppForm inline @submit.prevent="searchGoods">
        <AppFormItem>
          <AppInput v-model="goodsPicker.keyword" placeholder="商品名称/编码" clearable style="width: 220px" @keyup.enter="searchGoods" />
        </AppFormItem>
        <AppFormItem label="来源">
          <USwitch
            v-model="goodsPicker.onlySupplier"
            :disabled="editDialog.supplierId == null"
            @change="searchGoods"
          />
          <span class="switch-text">{{ goodsPicker.onlySupplier ? '仅该供应商供货商品' : '全量商品' }}</span>
        </AppFormItem>
        <AppFormItem>
          <UButton label="搜索" @click="searchGoods" />
        </AppFormItem>
      </AppForm>
      <UAlert
        v-if="goodsPicker.onlySupplier && editDialog.supplierId != null"
        color="info"
        variant="soft"
        icon="i-lucide-info"
        title="按供应商-商品关联带出供货价与最近供货价（FR-SUP-04）；选择商品后单价默认取最近供货价"
        class="picker-alert"
      />
      <AppTable :loading="goodsPicker.loading" :data="goodsPicker.list" size="small" stripe max-height="360">
        <AppTableColumn prop="goodsCode" label="编码" width="100" />
        <AppTableColumn prop="name" label="商品" min-width="150" show-overflow-tooltip />
        <AppTableColumn prop="spec" label="规格" width="90" show-overflow-tooltip />
        <AppTableColumn prop="unitName" label="单位" width="60" />
        <AppTableColumn v-if="goodsPicker.onlySupplier" label="最近供货价" width="100" align="right">
          <template #default="{ row }">{{ money(row.lastSupplyPrice) }}</template>
        </AppTableColumn>
        <AppTableColumn v-if="goodsPicker.onlySupplier" label="协议供货价" width="100" align="right">
          <template #default="{ row }">{{ money(row.supplyPrice) }}</template>
        </AppTableColumn>
        <AppTableColumn label="进货参考价" width="100" align="right">
          <template #default="{ row }">{{ money(row.purchasePrice) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="70" align="center">
          <template #default="{ row }">
            <UButton variant="link" size="sm" label="选择" @click="pickGoods(row as PickerGoodsRow)" />
          </template>
        </AppTableColumn>
      </AppTable>
    </AppModal>

    <!-- ==================== 审核（FR-PUR-04） ==================== -->
    <AppModal v-model="auditDialog.visible" :title="`审核 ${auditDialog.orderNo}`" width="440px">
      <AppForm label-width="80px" @submit.prevent>
        <AppFormItem label="审核结论">
          <AppRadioGroup v-model="auditDialog.approved">
            <AppRadio :value="true">通过（转已审核待入库）</AppRadio>
            <AppRadio :value="false">驳回（退回草稿）</AppRadio>
          </AppRadioGroup>
        </AppFormItem>
        <AppFormItem :label="auditDialog.approved ? '意见' : '驳回意见'" :required="!auditDialog.approved">
          <AppInput
            v-model="auditDialog.remark"
            type="textarea"
            :rows="3"
            maxlength="200"
            :placeholder="auditDialog.approved ? '选填' : '驳回时必填（FR-PUR-04）'"
          />
        </AppFormItem>
        <AppFormItem label="确认密码" required>
          <AppInput
            v-model="auditDialog.password"
            type="password"
            show-password
            maxlength="50"
            placeholder="当前账号密码（敏感操作二次确认）"
          />
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="auditDialog.visible = false" />
        <UButton :loading="auditDialog.saving" label="提交" @click="saveAudit" />
      </template>
    </AppModal>

    <!-- ==================== 结算登记（FR-PUR-09） ==================== -->
    <AppModal v-model="settleDialog.visible" :title="`结算登记 ${settleDialog.orderNo}`" width="460px">
      <UAlert color="info" variant="soft" icon="i-lucide-info" :title="`订购 ￥${money(settleDialog.totalAmount)}，实收 ￥${money(settleDialog.receivedAmount)}，退货 ￥${money(settleDialog.returnAmount)}；结算登记仅做标记，不做财务核销`" class="dialog-alert" />
      <AppForm label-width="90px" @submit.prevent>
        <AppFormItem label="结算状态" required>
          <AppRadioGroup v-model="settleDialog.form.settleStatus">
            <AppRadio value="SETTLED">已结算</AppRadio>
            <AppRadio value="PARTIAL">部分结算</AppRadio>
            <AppRadio value="UNSETTLED">未结算</AppRadio>
          </AppRadioGroup>
        </AppFormItem>
        <template v-if="settleDialog.form.settleStatus !== 'UNSETTLED'">
          <AppFormItem label="结算金额" required>
            <AppInputNumber v-model="settleDialog.form.settleAmount" :min="0" :precision="2" :controls="false" style="width: 180px" />
          </AppFormItem>
          <AppFormItem label="结算方式" required>
            <AppSelect v-model="settleDialog.form.settleMethod" style="width: 180px">
              <AppOption v-for="m in settleTypeOptions" :key="m.value" :label="m.label" :value="m.value" />
            </AppSelect>
          </AppFormItem>
          <AppFormItem label="结算日期">
            <AppDatePicker v-model="settleDialog.form.settleDate" type="date" value-format="YYYY-MM-DD" style="width: 180px" />
          </AppFormItem>
        </template>
      </AppForm>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="settleDialog.visible = false" />
        <UButton :loading="settleDialog.saving" label="保存" @click="saveSettle" />
      </template>
    </AppModal>

    <!-- ==================== 创建退货（FR-PUR-08） ==================== -->
    <AppModal v-model="returnDialog.visible" :title="`采购退货 ${returnDialog.purchaseOrderNo}`" width="720px">
      <UAlert color="warning" variant="soft" icon="i-lucide-triangle-alert" title="退货数量不可超过可退数量（已入库 − 已退）；退货单价按入库时成本单价计；创建后须店长审核通过才执行退货" class="dialog-alert" />
      <AppForm label-width="80px" @submit.prevent>
        <AppFormItem label="退货原因" required>
          <AppInput v-model="returnDialog.returnReason" maxlength="200" placeholder="如：临期质量问题、包装破损" />
        </AppFormItem>
        <AppTable :data="returnDialog.items" size="small" stripe>
          <AppTableColumn prop="goodsName" label="商品" min-width="170" show-overflow-tooltip />
          <AppTableColumn prop="unitName" label="单位" width="55" />
          <AppTableColumn label="可退数量" width="90" align="right" prop="returnable" />
          <AppTableColumn label="退货数量" width="140">
            <template #default="{ row }">
              <AppInputNumber v-model="row.returnQty" :min="0" :max="row.returnable" :precision="3" :controls="false" style="width: 120px" />
            </template>
          </AppTableColumn>
        </AppTable>
        <AppFormItem label="备注" style="margin-top: 10px">
          <AppInput v-model="returnDialog.remark" maxlength="200" placeholder="选填" />
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="returnDialog.visible = false" />
        <UButton :loading="returnDialog.saving" label="提交退货单" @click="saveReturn" />
      </template>
    </AppModal>

    <!-- ==================== 退货单详情 ==================== -->
    <AppDrawer v-model="returnDrawer.visible" title="退货单详情" size="640px">
      <div v-loading="returnDrawer.loading">
        <template v-if="returnDrawer.data">
          <AppDescriptions :column="2" border size="small">
            <AppDescriptionsItem label="退货单号">{{ returnDrawer.data.orderNo }}</AppDescriptionsItem>
            <AppDescriptionsItem label="状态">
              <UBadge :color="tagColor(RETURN_STATUS_MAP[returnDrawer.data.status]?.type ?? 'info')" variant="soft" size="sm">
                {{ RETURN_STATUS_MAP[returnDrawer.data.status]?.label ?? returnDrawer.data.status }}
              </UBadge>
            </AppDescriptionsItem>
            <AppDescriptionsItem label="原采购单">{{ returnDrawer.data.purchaseOrderNo }}</AppDescriptionsItem>
            <AppDescriptionsItem label="供应商">{{ returnDrawer.data.supplierName }}</AppDescriptionsItem>
            <AppDescriptionsItem label="退货金额">￥{{ money(returnDrawer.data.totalAmount) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="创建">{{ returnDrawer.data.createBy }} / {{ fmtTime(returnDrawer.data.createTime) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="审核">{{ returnDrawer.data.auditBy ?? '—' }} / {{ fmtTime(returnDrawer.data.auditTime) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="退货原因" :span="2">{{ returnDrawer.data.returnReason }}</AppDescriptionsItem>
            <AppDescriptionsItem v-if="returnDrawer.data.auditRemark" label="审核意见" :span="2">{{ returnDrawer.data.auditRemark }}</AppDescriptionsItem>
          </AppDescriptions>
          <AppTable :data="returnDrawer.data.items" stripe size="small" style="margin-top: 12px">
            <AppTableColumn prop="goodsCode" label="编码" width="100" />
            <AppTableColumn prop="goodsName" label="商品" min-width="140" show-overflow-tooltip />
            <AppTableColumn prop="unitName" label="单位" width="60" />
            <AppTableColumn label="退货数量" width="90" align="right" prop="returnQty" />
            <AppTableColumn label="退货单价" width="90" align="right">
              <template #default="{ row }">{{ money(row.returnPrice) }}</template>
            </AppTableColumn>
            <AppTableColumn label="金额" width="90" align="right">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </AppTableColumn>
          </AppTable>
        </template>
      </div>
    </AppDrawer>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sub {
  color: var(--ui-text-muted);
  font-size: 12px;
}

.progress-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.progress-bar {
  flex: 1;
  min-width: 0;
}

.progress-text {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--ui-text-muted);
}

.switch-text {
  margin-left: 8px;
  font-size: 12px;
  color: var(--ui-text-muted);
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.dialog-alert {
  margin-bottom: 12px;
}

.picker-alert {
  margin-bottom: 10px;
}

.suggested-cell {
  font-size: 12px;
  color: var(--ui-text-muted);
}

.items-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
  font-weight: 600;
}
</style>

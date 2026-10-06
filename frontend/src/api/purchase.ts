import { http } from './request'
import type { PageResult } from './request'

// ---------- 采购单（FR-PUR-01/03/04/05/09） ----------

/** 采购单状态（§2.2.1 状态机） */
export const PURCHASE_STATUS_MAP: Record<string, { label: string; type: 'info' | 'warning' | 'primary' | 'success' | 'danger' }> = {
  DRAFT: { label: '草稿', type: 'info' },
  SUBMITTED: { label: '待审核', type: 'warning' },
  APPROVED: { label: '已审核待入库', type: 'primary' },
  PARTIAL: { label: '部分入库', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' },
  TERMINATED: { label: '已终止', type: 'danger' },
  CANCELLED: { label: '已作废', type: 'danger' },
  PARTIAL_RETURNED: { label: '部分退货', type: 'warning' },
  ALL_RETURNED: { label: '全部退货', type: 'danger' },
}

/** 结算状态（FR-PUR-09） */
export const SETTLE_STATUS_MAP: Record<string, string> = {
  UNSETTLED: '未结算',
  PARTIAL: '部分结算',
  SETTLED: '已结算',
}

/** 结算方式（字典 settle_type） */
export const SETTLE_METHOD_MAP: Record<string, string> = {
  CASH: '现结',
  MONTHLY: '月结',
  CREDIT: '账期',
}

/** 采购单列表行（FR-PUR-03） */
export interface PurchaseOrderVO {
  id: number
  orderNo: string
  supplierId: number
  supplierName: string
  status: string
  createBy: string
  createTime: string
  submitTime: string | null
  auditBy: string | null
  auditTime: string | null
  expectDate: string | null
  totalAmount: number
  receivedAmount: number
  returnAmount: number
  orderedQty: number
  receivedQty: number
  receiveProgress: number
  settleStatus: string
  settleAmount: number | null
  terminateReason: string | null
  remark: string | null
}

/** 采购单详情（FR-PUR-01/03） */
export interface PurchaseOrderDetailVO extends PurchaseOrderVO {
  auditRemark: string | null
  settleMethod: string | null
  settleDate: string | null
  settleBy: string | null
  version: number
  items: PurchaseOrderItemVO[]
}

export interface PurchaseOrderItemVO {
  id: number
  goodsId: number
  goodsCode: string
  goodsName: string
  spec: string | null
  unitName: string | null
  quantity: number
  price: number
  amount: number
  receivedQty: number
  returnedQty: number
  pendingQty: number
  remark: string | null
}

export interface PurchaseOrderPageQuery {
  orderNo?: string
  supplierId?: number
  status?: string
  createBy?: string
  createDateBegin?: string
  createDateEnd?: string
  expectDateBegin?: string
  expectDateEnd?: string
  goodsKeyword?: string
  page: number
  size: number
}

export interface PurchaseItemPayload {
  goodsId: number
  quantity: number
  price: number
  remark?: string
}

export interface PurchaseSavePayload {
  supplierId: number
  expectDate?: string
  remark?: string
  items: PurchaseItemPayload[]
}

export function pagePurchaseOrders(params: PurchaseOrderPageQuery) {
  return http.get<PageResult<PurchaseOrderVO>>('/purchase-orders', params)
}

export function getPurchaseOrderDetail(id: number) {
  return http.get<PurchaseOrderDetailVO>(`/purchase-orders/${id}`)
}

export function createPurchaseOrder(data: PurchaseSavePayload) {
  return http.post<PurchaseOrderDetailVO>('/purchase-orders', data)
}

export function updatePurchaseOrder(id: number, data: PurchaseSavePayload) {
  return http.put<void>(`/purchase-orders/${id}`, data)
}

export function deletePurchaseOrder(id: number) {
  return http.delete<void>(`/purchase-orders/${id}`)
}

/** 提交审核（FR-PUR-04）：DRAFT → SUBMITTED */
export function submitPurchaseOrder(id: number) {
  return http.put<void>(`/purchase-orders/${id}/submit`)
}

/** 创建人撤回（FR-PUR-04）：SUBMITTED → DRAFT */
export function withdrawPurchaseOrder(id: number) {
  return http.put<void>(`/purchase-orders/${id}/withdraw`)
}

/** 店长审核（FR-PUR-04）：通过 → APPROVED；驳回须意见并退回草稿；password 为当前账号密码二次确认（BR-SYS-06） */
export function auditPurchaseOrder(id: number, approved: boolean, remark?: string, password?: string) {
  return http.put<void>(`/purchase-orders/${id}/audit`, { approved, remark, password })
}

/** 作废（FR-PUR-05）：仅草稿，须填原因 */
export function cancelPurchaseOrder(id: number, reason: string) {
  return http.put<void>(`/purchase-orders/${id}/cancel`, { reason })
}

/** 终止（FR-PUR-05）：仅已审核未入库，须填原因 */
export function terminatePurchaseOrder(id: number, reason: string) {
  return http.put<void>(`/purchase-orders/${id}/terminate`, { reason })
}

/** 关闭短少（FR-PUR-05）：部分入库剩余不再入库，转已完成 */
export function closePurchaseOrder(id: number, reason: string) {
  return http.put<void>(`/purchase-orders/${id}/close`, { reason })
}

/** 结算登记（FR-PUR-09）：标记结算状态并登记金额/方式/日期 */
export function settlePurchaseOrder(id: number, data: {
  settleStatus: string
  settleAmount: number
  settleMethod?: string
  settleDate?: string
}) {
  return http.put<void>(`/purchase-orders/${id}/settle`, data)
}

// ---------- 采购入库验收（FR-PUR-06/07） ----------

/** 入库单视图（FR-PUR-07）：详情含逐行实收数量/单价/金额，供 A4 打印 */
export interface ReceiptOrderVO {
  id: number
  orderNo: string
  purchaseOrderId: number
  purchaseOrderNo: string
  supplierId: number
  supplierName: string
  receiptTime: string
  operatorName: string
  authorizerName: string | null
  deliveryOrderNo: string | null
  totalAmount: number
  remark: string | null
  items: ReceiptOrderItemVO[]
}

export interface ReceiptOrderItemVO {
  id: number
  purchaseItemId: number
  goodsId: number
  goodsCode: string
  goodsName: string
  spec: string | null
  unitName: string | null
  receivedQty: number
  receivedPrice: number
  amount: number
  remark: string | null
}

/** 入库预检结果（BR-PUR-04/05）：容差上限、价差率与近 3 次均价黄色提示 */
export interface ReceiptPreCheckVO {
  purchaseOrderId: number
  purchaseOrderNo: string
  toleranceRate: number
  priceDiffRate: number
  priceWarnRate: number
  items: ReceiptItemCheckVO[]
}

export interface ReceiptItemCheckVO {
  purchaseItemId: number
  goodsId: number
  goodsName: string
  pendingQty: number
  toleranceMax: number
  receivedQty: number
  receivedPrice: number
  orderPrice: number
  priceDiffPercent: number
  recentAvgPrice: number | null
  priceWarning: boolean
  overTolerance: boolean
  priceAuthRequired: boolean
  needAuth: boolean
}

export interface ReceiptItemPayload {
  purchaseItemId: number
  receivedQty: number
  receivedPrice: number
  remark?: string
}

export interface ReceiptCreatePayload {
  purchaseOrderId: number
  /** 店长授权人（超收/价差超容差时必填，BR-PUR-04/05） */
  authorizerName?: string
  deliveryOrderNo?: string
  remark?: string
  items: ReceiptItemPayload[]
}

export interface ReceiptPageQuery {
  orderNo?: string
  purchaseOrderNo?: string
  supplierId?: number
  beginDate?: string
  endDate?: string
  operatorName?: string
  page: number
  size: number
}

export function pageReceiptOrders(params: ReceiptPageQuery) {
  return http.get<PageResult<ReceiptOrderVO>>('/receipt-orders', params)
}

export function getReceiptOrderDetail(id: number) {
  return http.get<ReceiptOrderVO>(`/receipt-orders/${id}`)
}

/** 入库预检（BR-PUR-04/05）：录入实收后返回容差/价差/均价提示 */
export function preCheckReceipt(data: ReceiptCreatePayload) {
  return http.post<ReceiptPreCheckVO>('/receipt-orders/precheck', data)
}

/** 入库验收（FR-PUR-06）：单事务落库并联动库存成本 */
export function createReceiptOrder(data: ReceiptCreatePayload) {
  return http.post<ReceiptOrderVO>('/receipt-orders', data)
}

// ---------- 采购退货（FR-PUR-08） ----------

export const RETURN_STATUS_MAP: Record<string, { label: string; type: 'warning' | 'success' | 'danger' }> = {
  SUBMITTED: { label: '待审核', type: 'warning' },
  APPROVED: { label: '已审核', type: 'success' },
  REJECTED: { label: '已驳回', type: 'danger' },
}

export interface PurchaseReturnVO {
  id: number
  orderNo: string
  purchaseOrderId: number
  purchaseOrderNo: string
  supplierId: number
  supplierName: string
  status: string
  returnReason: string
  totalAmount: number
  createBy: string
  createTime: string
  auditBy: string | null
  auditTime: string | null
  auditRemark: string | null
  remark: string | null
  items: PurchaseReturnItemVO[]
}

export interface PurchaseReturnItemVO {
  id: number
  purchaseItemId: number
  goodsId: number
  goodsCode: string
  goodsName: string
  spec: string | null
  unitName: string | null
  returnQty: number
  returnPrice: number
  amount: number
  remark: string | null
}

export interface PurchaseReturnPageQuery {
  orderNo?: string
  purchaseOrderNo?: string
  supplierId?: number
  status?: string
  beginDate?: string
  endDate?: string
  page: number
  size: number
}

export interface PurchaseReturnPayload {
  purchaseOrderId: number
  returnReason: string
  remark?: string
  items: { purchaseItemId: number; returnQty: number; remark?: string }[]
}

export function pagePurchaseReturns(params: PurchaseReturnPageQuery) {
  return http.get<PageResult<PurchaseReturnVO>>('/purchase-returns', params)
}

export function getPurchaseReturnDetail(id: number) {
  return http.get<PurchaseReturnVO>(`/purchase-returns/${id}`)
}

/** 创建退货单（FR-PUR-08）：勾选商品与退货数量（≤ 已入库 − 已退），待店长审核 */
export function createPurchaseReturn(data: PurchaseReturnPayload) {
  return http.post<PurchaseReturnVO>('/purchase-returns', data)
}

/** 退货审核（FR-PUR-08）：通过后事务内执行退货；驳回须填意见；password 为当前账号密码二次确认（BR-SYS-06） */
export function auditPurchaseReturn(id: number, approved: boolean, remark?: string, password?: string) {
  return http.put<void>(`/purchase-returns/${id}/audit`, { approved, remark, password })
}

// ---------- 建议采购清单（FR-PUR-02，BR-INV-07） ----------

/** 建议采购清单行：建议量 = ceil(安全库存×K + 日均×提前期 − 结存 − 在途) */
export interface SuggestItemVO {
  goodsId: number
  goodsCode: string
  goodsName: string
  spec: string | null
  categoryId: number
  categoryName: string | null
  unitName: string | null
  isWeighed: boolean
  /** OUT 缺货 / WARNING 预警（FR-INV-05 口径） */
  suggestType: string
  quantity: number
  safetyStockLower: number
  soldQty: number
  avgDailySales: number
  onWayQty: number
  leadTimeDays: number
  suggestQty: number
  calcBasis: string
  supplierId: number | null
  supplierName: string | null
  supplyPrice: number | null
  lastPurchasePrice: number | null
}

export interface SuggestPageQuery {
  name?: string
  goodsCode?: string
  categoryId?: number
  alertType?: string
  page: number
  size: number
}

export function pageSuggestPurchase(params: SuggestPageQuery) {
  return http.get<PageResult<SuggestItemVO>>('/suggest-purchase', params)
}

/** 一键生成草稿（FR-PUR-02）：按默认供应商分组；跳过商品返回原因 */
export function generateSuggestOrders(items: { goodsId: number; quantity?: number; price?: number }[]) {
  return http.post<{ orders: { orderId: number; orderNo: string; supplierName: string; itemCount: number; totalAmount: number }[]; skippedGoods: { goodsId: number; goodsName: string; reason: string }[] }>(
    '/suggest-purchase/generate',
    { items },
  )
}

import { http } from './request'
import type { PageResult } from './request'

// ---------- 收银结算（FR-SALE-06） ----------

/** 收款方式：CASH现金 / BANK银行卡 / MOBILE移动支付 / BALANCE会员储值（仅会员单，FR-MEM-08） */
export type PayType = 'CASH' | 'BANK' | 'MOBILE' | 'BALANCE'

export const PAY_TYPE_MAP: Record<string, string> = {
  CASH: '现金',
  BANK: '银行卡',
  MOBILE: '移动支付',
  BALANCE: '储值',
}

/** 退货原因（字典 return_reason）：QUALITY质量问题 / WRONG_ITEM错拿 / MISTAKE误操作 / OTHER其他 */
export type ReturnReason = 'QUALITY' | 'WRONG_ITEM' | 'MISTAKE' | 'OTHER'

export const RETURN_REASON_MAP: Record<string, string> = {
  QUALITY: '质量问题',
  WRONG_ITEM: '错拿商品',
  MISTAKE: '收银误操作',
  OTHER: '其他',
}

/** 退货状态：NONE未退 / PARTIAL部分退货 / ALL全部退货 */
export const RETURN_STATUS_MAP: Record<string, { label: string; type: 'info' | 'warning' | 'success' }> = {
  NONE: { label: '未退货', type: 'info' },
  PARTIAL: { label: '部分退货', type: 'warning' },
  ALL: { label: '全部退货', type: 'success' },
}

/** 结算命令（FR-SALE-06）：单价由后端按取价规则解析（BR-GOODS-04），前端不传价 */
export interface CheckoutPayload {
  /** 收银台号 1~99 */
  counterNo: number
  /** 会员ID（散客为空，FR-SALE-05） */
  memberId?: number
  /** 店长整单折扣率 0.7~1（BR-SALE-10） */
  orderDiscountRate?: number
  /** 店长授权人姓名（整单折扣时必填，FR-SALE-07） */
  authorizerName?: string
  remark?: string
  items: {
    /** 商品ID（无码商品为空，FR-SALE-13） */
    goodsId?: number
    /** 无码商品名称（goodsId 为空时必填，FR-SALE-13） */
    tempName?: string
    /** 无码商品金额（折前行金额，goodsId 为空时必填，FR-SALE-13） */
    tempAmount?: number
    /** 数量：普通商品正整数；称重商品为净重（≤3 位小数，BR-SALE-01）；无码商品恒为 1 */
    quantity: number
    /** 净重（称重商品必填） */
    netWeight?: number
    remark?: string
  }[]
  /** 一单多方式组合收款（FR-SALE-06） */
  payments: { payType: PayType; amount: number }[]
}

/** 结算结果（FR-SALE-06）：单号与找零回显，供小票预览 */
export interface CheckoutVO {
  orderId: number
  orderNo: string
  itemCount: number
  goodsAmount: number
  memberDiscountAmount: number
  orderDiscountAmount: number
  discountAmount: number
  payableAmount: number
  paidAmount: number
  changeAmount: number
  payments: { payType: PayType; amount: number }[]
  /** 本次结算触发的升级等级名（未升级为 null，FR-MEM-06 收银台提示） */
  memberLevelUpgraded: string | null
  saleTime: string
}

/** 收银结算 */
export function checkout(data: CheckoutPayload) {
  return http.post<CheckoutVO>('/sales/checkout', data, 30000)
}

// ---------- 收银台会员检索（FR-SALE-05） ----------

export interface MemberSearchVO {
  id: number
  memberNo: string
  name: string
  /** 手机号（脱敏 138****5678，BR-MEM-08 收银台场景） */
  phone: string
  levelName: string
  /** 等级折扣率（停用会员返回 1，不享受折扣，BR-MEM-03） */
  discountRate: number
  points: number
  /** 储值余额（BALANCE 支付上限参考，BR-MEM-07） */
  balance: number
  /** 状态：1正常 0停用 */
  status: number
}

/** 会员检索：手机号/会员编号精确 + 姓名模糊，最多 10 条 */
export function searchMembers(keyword: string) {
  return http.get<MemberSearchVO[]>('/sales/members', { keyword })
}

// ---------- 销售单查询（FR-SALE-09） ----------

export interface SalesOrderVO {
  id: number
  orderNo: string
  counterNo: number
  cashierName: string
  memberName: string | null
  memberPhone: string | null
  saleTime: string
  itemCount: number
  goodsAmount: number
  discountAmount: number
  payableAmount: number
  paidAmount: number
  changeAmount: number
  returnStatus: string
  /** 累计退款金额 */
  refundAmount: number
}

export interface SalesOrderQuery {
  orderNo?: string
  cashierName?: string
  counterNo?: number
  memberKeyword?: string
  payType?: string
  beginDate?: string
  endDate?: string
  amountMin?: number
  amountMax?: number
  returnStatus?: string
  memberOnly?: boolean
  page: number
  size: number
}

/** 销售明细快照（BR-SALE-04） */
export interface SalesOrderItemVO {
  id: number
  goodsId: number
  goodsName: string
  barcode: string | null
  unitPrice: number
  quantity: number
  isWeighed: number
  netWeight: number | null
  amount: number
  discountRate: number
  discountAmount: number
  paidAmount: number
  costPrice: number
  costAmount: number
  profitAmount: number
  returnedQty: number
  lineType: string
  remark: string | null
}

export interface SalesOrderDetailVO {
  id: number
  orderNo: string
  counterNo: number
  cashierName: string
  memberId: number | null
  memberName: string | null
  memberPhone: string | null
  saleTime: string
  businessDate: string
  itemCount: number
  goodsAmount: number
  memberDiscountAmount: number
  orderDiscountAmount: number
  discountAmount: number
  payableAmount: number
  paidAmount: number
  changeAmount: number
  orderDiscountRate: number | null
  costAmount: number
  profitAmount: number
  returnStatus: string
  authorizerName: string | null
  remark: string | null
  items: SalesOrderItemVO[]
  payments: { payType: string; amount: number; payTime: string; operatorName: string }[]
  returns: {
    id: number
    orderNo: string
    refundAmount: number
    returnReason: string
    refundType: string
    cashierName: string
    authorizerName: string | null
    returnTime: string
  }[]
}

export function pageSalesOrders(params: SalesOrderQuery) {
  return http.get<PageResult<SalesOrderVO>>('/sales', params)
}

export function getSalesOrderDetail(id: number) {
  return http.get<SalesOrderDetailVO>(`/sales/${id}`)
}

// ---------- 销售退货（FR-SALE-10/11） ----------

export interface ReturnOrderVO {
  id: number
  orderNo: string
  salesOrderId: number
  salesOrderNo: string
  memberName: string | null
  memberPhone: string | null
  refundAmount: number
  returnReason: string
  refundType: string
  cashierName: string
  authorizerName: string | null
  returnTime: string
}

export interface ReturnQuery {
  orderNo?: string
  salesOrderNo?: string
  memberKeyword?: string
  refundType?: string
  returnReason?: string
  beginDate?: string
  endDate?: string
  page: number
  size: number
}

export interface ReturnOrderDetailVO {
  id: number
  orderNo: string
  salesOrderId: number
  salesOrderNo: string
  salesTime: string
  memberName: string | null
  memberPhone: string | null
  returnReason: string
  reasonRemark: string | null
  refundAmount: number
  refundType: string
  cashierName: string
  authorizerName: string | null
  authorizeTime: string | null
  returnTime: string
  businessDate: string
  items: {
    id: number
    salesItemId: number
    goodsId: number
    goodsName: string
    barcode: string | null
    returnQty: number
    refundPrice: number
    refundAmount: number
    costPrice: number
    costAmount: number
    remark: string | null
  }[]
}

/** 办理退货请求（FR-SALE-10）：按原销售明细行退，退款由后端按原成交单价计算（BR-SALE-06） */
export interface ReturnCreatePayload {
  salesOrderId: number
  refundType: PayType
  returnReason: ReturnReason
  reasonRemark?: string
  /** 店长授权人姓名（退款超阈值或误操作时必填，FR-SALE-11） */
  authorizerName?: string
  items: {
    /** 原销售明细行ID */
    salesItemId: number
    returnQty: number
    remark?: string
  }[]
}

export function createReturn(data: ReturnCreatePayload) {
  return http.post<ReturnOrderDetailVO>('/sales/returns', data, 30000)
}

export function pageReturns(params: ReturnQuery) {
  return http.get<PageResult<ReturnOrderVO>>('/sales/returns', params)
}

export function getReturnDetail(id: number) {
  return http.get<ReturnOrderDetailVO>(`/sales/returns/${id}`)
}

// ---------- 交班管理（FR-SALE-12） ----------

/** 班次状态：WORKING在班 / CLOSED已交班 */
export type ShiftStatus = 'WORKING' | 'CLOSED'

export const SHIFT_STATUS_MAP: Record<string, { label: string; type: 'success' | 'info' }> = {
  WORKING: { label: '在班', type: 'success' },
  CLOSED: { label: '已交班', type: 'info' },
}

export interface ShiftVO {
  id: number
  cashierId: number
  cashierName: string
  counterNo: number
  startTime: string
  endTime: string | null
  orderCount: number
  /** 净销售 = Σ应收 − 退货额 */
  salesAmount: number
  cashAmount: number
  bankAmount: number
  mobileAmount: number
  balanceAmount: number
  returnAmount: number
  /** 应收现金 = 现金收款 − 现金退款 */
  expectedCash: number
  status: ShiftStatus
}

export interface ShiftQuery {
  cashierName?: string
  counterNo?: number
  status?: string
  beginDate?: string
  endDate?: string
  page: number
  size: number
}

/** 班次内销售单摘要行（交班单） */
export interface ShiftOrderLine {
  id: number
  orderNo: string
  saleTime: string
  itemCount: number
  payableAmount: number
  paidAmount: number
  changeAmount: number
  returnStatus: string
  refundAmount: number
}

export interface ShiftDetailVO {
  shift: ShiftVO
  orders: ShiftOrderLine[]
}

/** 开班登记（FR-SALE-12）：同一收银员同一时刻仅一个在班班次 */
export function openShift(counterNo: number) {
  return http.post<ShiftVO>(`/sales/shifts/open?counterNo=${counterNo}`)
}

/** 交班对账（FR-SALE-12）：返回交班单（含班次内销售单，供打印核对） */
export function closeShift() {
  return http.post<ShiftDetailVO>('/sales/shifts/close')
}

/** 当前在班班次（收银台展示；无在班班次返回 null） */
export function getCurrentShift() {
  return http.get<ShiftVO | null>('/sales/shifts/current')
}

export function pageShifts(params: ShiftQuery) {
  return http.get<PageResult<ShiftVO>>('/sales/shifts', params)
}

export function getShiftDetail(id: number) {
  return http.get<ShiftDetailVO>(`/sales/shifts/${id}`)
}

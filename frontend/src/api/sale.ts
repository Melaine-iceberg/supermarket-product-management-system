import { http } from './request'
import type { PageResult } from './request'

// ---------- 收银结算（FR-SALE-06） ----------

/** 收款方式内置展示（数据字典 pay_type，FR-SYS-07；字典新增取值时按原值显示） */
export const PAY_TYPE_MAP: Record<string, string> = {
  CASH: '现金',
  BANK: '银行卡',
  MOBILE: '移动支付',
  BALANCE: '储值',
}

/** 退货原因内置展示（数据字典 return_reason，FR-SYS-07；字典新增取值时按原值显示） */
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
  /** 店长授权账号（整单折扣/单品改价时必填，FR-SALE-07：店长现场输入账号密码授权） */
  authorizerUsername?: string
  /** 店长授权密码（与授权账号一并校验） */
  authorizerPassword?: string
  /** 整单折扣原因（整单折扣时必填，FR-SALE-07） */
  discountReason?: string
  remark?: string
  /** 请求令牌（NFR-REL-02 防重复提交；同令牌重放返回既有单据） */
  requestToken?: string
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
    /** 店长改价单价（仅档案商品，FR-SALE-07） */
    overridePrice?: number
    /** 改价原因（改价时必填） */
    priceReason?: string
  }[]
  /** 一单多方式组合收款（FR-SALE-06）；payType 取值来自数据字典 pay_type 启用项（FR-SYS-07） */
  payments: { payType: string; amount: number }[]
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
  payments: { payType: string; amount: number }[]
  /** 本次结算触发的升级等级名（未升级为 null，FR-MEM-06 收银台提示） */
  memberLevelUpgraded: string | null
  saleTime: string
}

/** 收银结算 */
export function checkout(data: CheckoutPayload) {
  return http.post<CheckoutVO>('/sales/checkout', data, 30000)
}

// ---------- 收银台商品检索（FR-SALE-02） ----------

/** 收银检索候选：条码/编码/PLU 精确命中优先，名称/拼音首字母模糊次之 */
export interface CashierGoodsVO {
  id: number
  goodsCode: string
  barcode: string | null
  name: string
  spec: string | null
  pluCode: string | null
  unitName: string
  retailPrice: number
  isWeighed: number
}

/** 商品关键字检索：条码/商品编码/PLU 码/名称/拼音首字母，仅启用商品，最多 20 条 */
export function searchCashierGoods(keyword: string) {
  return http.get<CashierGoodsVO[]>('/sales/cashier/goods', { keyword })
}

// ---------- 收银台分类树浏览（GAP-P0-06，FR-SALE-02③：按 sys:cashier:enter 鉴权的只读接口） ----------

/** 收银台分类节点（仅启用分类，字段与商品分类树对齐） */
export interface CashierCategoryNode {
  id: number
  parentId: number
  name: string
  level: number
  sortNo: number
  status: number
  leaf: boolean
  goodsCount: number
  children: CashierCategoryNode[]
}

/** 收银台分类树（仅启用分类；收银员无 sys:cat:list 权限亦可浏览） */
export function getCashierCategoryTree() {
  return http.get<CashierCategoryNode[]>('/sales/cashier/categories')
}

/** 收银台分类浏览商品：分类含子分类、仅启用商品、分页返回 */
export function pageCashierGoods(params: { categoryId?: number; page: number; size: number }) {
  return http.get<PageResult<CashierGoodsVO>>('/sales/cashier/browse', params)
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
  /** 改价前按取价规则解析的单价（未改价 null，FR-SALE-07） */
  originalUnitPrice: number | null
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
  /** 改价原因（未改价 null，FR-SALE-07） */
  priceReason: string | null
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
  /** 整单折扣原因（FR-SALE-07） */
  discountReason: string | null
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
  /** 退款方式：数据字典 pay_type 启用项（FR-SYS-07） */
  refundType: string
  /** 退货原因：数据字典 return_reason 启用项（FR-SYS-07） */
  returnReason: string
  reasonRemark?: string
  /** 店长授权账号（退款超阈值/误操作/超期特批时填写，FR-SALE-11：店长账号+密码授权） */
  authorizerUsername?: string
  /** 店长授权密码（与授权账号一并校验） */
  authorizerPassword?: string
  items: {
    /** 原销售明细行ID */
    salesItemId: number
    returnQty: number
    remark?: string
  }[]
  /** 请求令牌（NFR-REL-02 防重复提交；同令牌重放返回既有单据） */
  requestToken?: string
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

// ---------- 挂单与取单（FR-SALE-08） ----------

/** 挂单明细快照行（取单后原样恢复购物车；金额摘要由后端按改价价 ?? 零售价 × 数量计算） */
export interface HoldItemPayload {
  lineType: 'NORMAL' | 'TEMP'
  goodsId?: number
  name: string
  spec?: string | null
  barcode?: string | null
  unitName: string
  retailPrice: number
  isWeighed: number
  quantity: number
  remark?: string
  overridePrice?: number
  priceReason?: string
}

export interface HoldCreatePayload {
  counterNo: number
  memberId?: number
  memberName?: string
  orderRate?: number
  discountReason?: string
  authorizerUsername?: string
  remark?: string
  items: HoldItemPayload[]
}

/** 挂单摘要（列表展示：时间/件数/金额/会员，超 24 小时自动清理 IR-SW-06） */
export interface HoldVO {
  id: number
  counterNo: number
  cashierName: string
  memberId: number | null
  memberName: string | null
  itemCount: number
  amount: number
  remark: string | null
  holdTime: string
}

/** 取单详情（含明细快照与挂单时的会员/折扣信息；授权密码不落库，结算时重新录入） */
export interface HoldDetailVO extends HoldVO {
  orderRate: number | null
  discountReason: string | null
  authorizerUsername: string | null
  items: HoldItemPayload[]
}

/** 挂单（FR-SALE-08）：不扣库存、不计入报表（BR-RPT-03） */
export function createHold(data: HoldCreatePayload) {
  return http.post<HoldVO>('/sales/holds', data)
}

/** 本人未取挂单列表（挂单时间倒序） */
export function listHolds() {
  return http.get<HoldVO[]>('/sales/holds')
}

/** 取单：ACTIVE→TAKEN 条件更新防并发重复取单，返回明细快照 */
export function takeHold(id: number) {
  return http.post<HoldDetailVO>(`/sales/holds/${id}/take`)
}

/** 删除挂单：ACTIVE→DELETED 条件更新 */
export function deleteHold(id: number) {
  return http.delete<void>(`/sales/holds/${id}`)
}

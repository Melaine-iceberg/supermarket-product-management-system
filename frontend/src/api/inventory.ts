import { http } from './request'
import { runExportTask } from './task'
import type { PageResult } from './request'

// ---------- 盘点管理（FR-INV-07~09，§2.2.4 状态机） ----------

/** 盘点单状态：盘点中 → 待审核 → 已审核 / 已作废 */
export type CheckStatus = 'CHECKING' | 'PENDING_AUDIT' | 'AUDITED' | 'CANCELLED'

/** 盘点范围类型（FR-INV-07） */
export type CheckScopeType = 'ALL' | 'CATEGORY' | 'GOODS' | 'WARNING'

/** 盘点单列表行（FR-INV-07）：单头 + 盘盈/盘亏汇总 */
export interface CheckOrderVO {
  id: number
  orderNo: string
  scopeType: CheckScopeType
  scopeDesc: string
  status: CheckStatus
  checkerName: string
  startTime: string
  baseTime: string
  itemCount: number
  /** 已录入实盘数的行数（0~itemCount） */
  enteredCount: number
  /** 差异行数（diffQty≠0） */
  diffCount: number
  surplusAmount: number
  shortageAmount: number
  /** 盘点期间出入库笔数（BR-INV-10，审核时统计） */
  duringChangeCount: number
  auditorName: string | null
  auditTime: string | null
  auditRemark: string | null
  cancelReason: string | null
  createTime: string
}

/** 盘点明细行（FR-INV-08）：账面/实盘/差异 + 商品信息（keyword 扫码定位行） */
export interface CheckItemVO {
  id: number
  goodsId: number
  goodsCode: string
  barcode: string | null
  name: string
  categoryId: number
  categoryName: string
  unitName: string
  spec: string | null
  isWeighed: number
  bookQty: number
  actualQty: number | null
  diffQty: number | null
  costPrice: number
  diffAmount: number | null
  diffReason: string | null
  remark: string | null
}

/** 盘点实时汇总（FR-INV-08）：已录入行数、差异行数、盘盈/盘亏总额 */
export interface CheckSummaryVO {
  enteredCount: number
  diffCount: number
  surplusAmount: number
  shortageAmount: number
}

/** 盘点单详情：单头 + 明细分页 */
export interface CheckOrderDetailVO {
  order: CheckOrderVO
  items: PageResult<CheckItemVO>
}

/** 盘点单列表查询条件（FR-INV-07） */
export interface CheckListQuery {
  orderNo?: string
  status?: string
  checkerName?: string
  auditorName?: string
  beginDate?: string
  endDate?: string
  page: number
  size: number
}

/** 创建盘点单请求（FR-INV-07）：CATEGORY 须 categoryId，GOODS 须 goodsIds */
export interface CheckCreatePayload {
  scopeType: CheckScopeType
  categoryId?: number
  goodsIds?: number[]
  remark?: string
}

/** 实盘数量录入请求（FR-INV-08）：差异由服务端计算，差异≠0 行原因必填 */
export interface CheckEntryPayload {
  items: {
    goodsId: number
    actualQty: number
    diffReason?: string
    remark?: string
  }[]
}

/** 盘点单分页查询 */
export function pageStockChecks(params: CheckListQuery) {
  return http.get<PageResult<CheckOrderVO>>('/stock-checks', params)
}

/** 创建盘点单（FR-INV-07）：快照账面数量，返回单号供录入 */
export function createStockCheck(data: CheckCreatePayload) {
  return http.post<CheckOrderVO>('/stock-checks', data)
}

/** 盘点单详情（FR-INV-07/08）：onlyDiff 仅看差异行，keyword 匹配编码/条码/名称 */
export function getStockCheckDetail(
  id: number,
  params: { page: number; size: number; onlyDiff?: boolean; keyword?: string },
) {
  return http.get<CheckOrderDetailVO>(`/stock-checks/${id}`, params)
}

/** 作废盘点单（§2.2.4：仅盘点中可作废），须填写原因 */
export function cancelStockCheck(id: number, reason: string) {
  return http.post<void>(`/stock-checks/${id}/cancel`, { reason })
}

/** 录入实盘数量（FR-INV-08）：逐行/批量录入，返回实时汇总 */
export function enterStockCheck(id: number, data: CheckEntryPayload) {
  return http.put<CheckSummaryVO>(`/stock-checks/${id}/items`, data)
}

/** 提交审核（FR-INV-08）：未盘行按账面确认，状态→待审核 */
export function submitStockCheck(id: number) {
  return http.post<CheckSummaryVO>(`/stock-checks/${id}/submit`)
}

/** 审核盘点单（FR-INV-09）：approved=true 通过（覆盖结存生成流水），false 驳回（意见必填）；password 为当前账号密码二次确认（BR-SYS-06） */
export function auditStockCheck(id: number, approved: boolean, remark?: string, password?: string) {
  return http.post<CheckOrderVO>(`/stock-checks/${id}/audit`, { approved, remark, password })
}

/** 实盘录入模板下载（FR-INV-08）：按盘点单明细导出 xlsx */
export function downloadCheckTemplate(id: number) {
  return http.download(`/stock-checks/${id}/items/template`)
}

/** Excel 批量导入实盘数（FR-INV-08） */
export function importCheckActual(id: number, file: File) {
  return http.upload<{ importedCount: number; summary: CheckSummaryVO }>(
    `/stock-checks/${id}/items/import`,
    file,
  )
}

// ---------- 报损/报溢登记（FR-INV-10） ----------

/** 报损报溢类型：LOSS报损出库 / OVERFLOW报溢入库 */
export type LossBizType = 'LOSS' | 'OVERFLOW'

/** 单据状态：PENDING待审核 → AUDITED已审核 / REJECTED已驳回 */
export type LossStatus = 'PENDING' | 'AUDITED' | 'REJECTED'

/** 报损/报溢单（单商品）：reason 存字典代码，reasonLabel 为展示名（FR-SYS-07） */
export interface LossOrderVO {
  id: number
  orderNo: string
  bizType: LossBizType
  goodsId: number
  goodsCode: string
  barcode: string | null
  goodsName: string
  categoryName: string
  unitName: string
  spec: string | null
  isWeighed: number
  quantity: number
  /** 报损=登记时成本快照（审核时按实际结转定稿）；报溢=录入单价 */
  costPrice: number
  amount: number
  reason: string
  reasonLabel: string
  applyName: string
  status: LossStatus
  auditorName: string | null
  auditTime: string | null
  auditRemark: string | null
  remark: string | null
  createTime: string
}

/** 报损/报溢登记请求（FR-INV-10）：报溢须录入成本单价，报损由服务端按当前成本快照 */
export interface LossOrderCreatePayload {
  bizType: LossBizType
  goodsId: number
  quantity: number
  costPrice?: number
  reason: string
  remark?: string
}

/** 报损/报溢单列表查询条件（FR-INV-10） */
export interface LossOrderQuery {
  orderNo?: string
  bizType?: string
  status?: string
  goodsKeyword?: string
  applyName?: string
  beginDate?: string
  endDate?: string
  page: number
  size: number
}

/** 报损/报溢单分页（FR-INV-10） */
export function pageLossOrders(params: LossOrderQuery) {
  return http.get<PageResult<LossOrderVO>>('/loss-orders', params)
}

/** 登记报损/报溢单（FR-INV-10）：登记后进入待审核 */
export function createLossOrder(data: LossOrderCreatePayload) {
  return http.post<LossOrderVO>('/loss-orders', data)
}

/** 单据详情（FR-INV-10） */
export function getLossOrder(id: number) {
  return http.get<LossOrderVO>(`/loss-orders/${id}`)
}

/** 店长审核（FR-INV-10）：approved=true 通过（生成 LOSS_OUT/OVERFLOW_IN 流水），false 驳回（意见必填）；password 为当前账号密码二次确认（BR-SYS-06） */
export function auditLossOrder(id: number, approved: boolean, remark?: string, password?: string) {
  return http.post<LossOrderVO>(`/loss-orders/${id}/audit`, { approved, remark, password })
}

// ---------- 实时库存查询（FR-INV-01，只读：结存为派生数据 BR-INV-11） ----------

/** 库存状态：NORMAL正常 / WARNING预警 / OUT缺货 / OVERSTOCK积压（FR-INV-05 口径） */
export type StockStatus = 'NORMAL' | 'WARNING' | 'OUT' | 'OVERSTOCK'

export interface InventoryItemVO {
  goodsId: number
  goodsCode: string
  barcode: string | null
  name: string
  categoryId: number
  categoryName: string
  unitName: string
  spec: string | null
  isWeighed: number
  quantity: number
  costPrice: number
  costAmount: number
  retailPrice: number
  safetyStockLower: number
  safetyStockUpper: number | null
  stockStatus: StockStatus
  lastChangeTime: string | null
}

/** 库存分页 + 合计：totalCostAmount 为当前筛选范围库存总成本 */
export interface InventoryPageVO {
  list: InventoryItemVO[]
  total: number
  totalCostAmount: number
}

/** 排序白名单（与后端 InventoryMapper 一致）：goodsCode/name/quantity/costAmount/lastChangeTime */
export interface InventoryQuery {
  name?: string
  goodsCode?: string
  categoryId?: number
  stockStatus?: string
  page: number
  size: number
  sortBy?: string
  sortDir?: string
}

export function pageInventory(params: InventoryQuery) {
  return http.get<InventoryPageVO>('/inventory', params)
}

// ---------- 安全库存阈值维护（FR-INV-06） ----------

/** 逐商品设置阈值（全量覆盖目标值），返回实际变更数 */
export function updateSafetyStock(items: {
  goodsId: number
  safetyStockLower?: number
  safetyStockUpper?: number | null
}[]) {
  return http.put<number>('/inventory/safety-stock', { items })
}

/** 按分类（含子分类）批量设置默认阈值，返回实际变更数 */
export function updateSafetyStockByCategory(data: {
  categoryId: number
  safetyStockLower?: number
  safetyStockUpper?: number | null
}) {
  return http.put<number>('/inventory/safety-stock/by-category', data)
}

// ---------- 库存流水（FR-INV-03，只读 BR-INV-04） ----------

export interface InventoryRecordVO {
  id: number
  goodsId: number
  goodsCode: string
  goodsName: string
  unitName: string
  recordType: string
  changeQty: number
  beforeQty: number
  afterQty: number
  unitCost: number
  bizType: string | null
  bizNo: string | null
  operatorName: string
  remark: string | null
  operateTime: string
}

export function pageInventoryRecords(params: {
  goodsName?: string
  recordType?: string
  bizNo?: string
  operatorName?: string
  beginDate?: string
  endDate?: string
  page: number
  size: number
}) {
  return http.get<PageResult<InventoryRecordVO>>('/inventory/records', params)
}

// ---------- 库存预警（FR-INV-05） ----------

export type AlertType = 'OUT' | 'WARNING' | 'OVERSTOCK'

export interface AlertItemVO {
  goodsId: number
  goodsCode: string
  barcode: string | null
  name: string
  categoryId: number
  categoryName: string
  unitName: string
  spec: string | null
  isWeighed: number
  quantity: number
  safetyStockLower: number
  safetyStockUpper: number | null
  alertType: AlertType
  /** 缺口量：缺货/预警=下限-结存，积压=结存-上限 */
  gapQty: number
}

/** 预警数量汇总（看板角标 / 页签数量） */
export interface AlertSummaryVO {
  outCount: number
  warningCount: number
  overstockCount: number
}

export interface AlertPageVO {
  list: AlertItemVO[]
  total: number
  summary: AlertSummaryVO
}

/** 排序白名单：gap缺口量/quantity/goodsCode/name */
export interface AlertQuery {
  name?: string
  goodsCode?: string
  categoryId?: number
  alertType?: string
  page: number
  size: number
  sortBy?: string
  sortDir?: string
}

export function pageAlerts(params: AlertQuery) {
  return http.get<AlertPageVO>('/inventory/alerts', params)
}

/** 预警清单导出（FR-INV-05）：与列表同一筛选条件，返回 xlsx blob */
export function exportAlerts(params: Omit<AlertQuery, 'page' | 'size'>) {
  // 异步任务：提交后轮询进度，完成后返回结果文件（FR-INV-05）
  return runExportTask('/inventory/alerts/export', params)
}

// ---------- 库存体检（FR-INV-11，白盒校验 BR-INV-08） ----------

/** 差异行：账面结存 vs 理论结存（Σ流水），差异 = 账面 − 理论 */
export interface InventoryHealthRow {
  goodsId: number
  goodsCode: string
  goodsName: string
  categoryName: string
  /** 账面结存（结存行缺失按 0 处理） */
  bookQty: number
  /** 理论结存 = 期初 + Σ流水数量（BR-INV-05/08） */
  recordQty: number
  /** 差异 = 账面 − 理论 */
  diffQty: number
  costPrice: number
  lastChangeTime: string | null
  /** 结存行缺失（修正时自动补行） */
  rowMissing: boolean
}

export interface InventoryHealthVO {
  rows: InventoryHealthRow[]
  /** 差异商品总数（分页 total） */
  total: number
  /** 参与体检的商品数（口径：全部商品档案） */
  checkedSkus: number
}

/** 库存体检（FR-INV-11）：按流水重算理论结存并比对，返回差异清单 */
export function checkInventoryHealth(params: { page: number; size: number }) {
  return http.get<InventoryHealthVO>('/inventory/health', params)
}

/** 一键按流水修正结存（FR-INV-11）：仅超级管理员，返回修正商品数 */
export function fixInventoryHealth() {
  return http.post<number>('/inventory/health/fix')
}

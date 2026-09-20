import { http } from './request'
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

/** 审核盘点单（FR-INV-09）：approved=true 通过（覆盖结存生成流水），false 驳回（意见必填） */
export function auditStockCheck(id: number, approved: boolean, remark?: string) {
  return http.post<CheckOrderVO>(`/stock-checks/${id}/audit`, { approved, remark })
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

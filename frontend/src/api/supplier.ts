import { http } from './request'
import type { PageResult } from './request'

// ---------- 供应商档案（FR-SUP-01~04） ----------

/** 供应商列表/详情视图（FR-SUP-02）：含在途/未结算/最近合作等业务统计 */
export interface SupplierListVO {
  id: number
  /** 供应商编码：S+5位流水，创建后不可修改（BR-SUP-03） */
  supplierCode: string
  name: string
  contactPerson: string | null
  phone: string | null
  address: string | null
  email: string | null
  bankName: string | null
  /** 银行账号：无权限角色脱敏 ****XXXX（BR-SUP-01） */
  bankAccount: string | null
  /** 纳税人识别号：无权限角色不返回（BR-SUP-01） */
  taxNo: string | null
  /** 结算方式：CASH现结/MONTHLY月结/CREDIT账期 */
  settleType: string
  creditDays: number | null
  /** 采购提前期（天） */
  leadTimeDays: number
  /** 累计采购额（派生，入库验收时更新） */
  totalPurchaseAmount: number
  /** 未结算金额（FR-SUP-02） */
  unsettledAmount: number
  /** 最近合作时间（最近一次入库） */
  lastCoopTime: string | null
  /** 在途采购单数（已提交/已审核未完成） */
  inTransitCount: number
  /** 供货商品数 */
  goodsCount: number
  /** 状态：1启用 0停用（停用后不可被新采购单选择，FR-SUP-03） */
  status: number
  remark: string | null
  createTime: string
}

/** 列表查询条件（FR-SUP-02）：名称/编码/联系人/电话模糊，状态精确，供货品类筛选 */
export interface SupplierQuery {
  name?: string
  code?: string
  contactPerson?: string
  phone?: string
  status?: number
  categoryId?: number
  page: number
  size: number
}

/** 新增/编辑请求（FR-SUP-01）：编码系统生成；脱敏字段原值提交由后端保留 */
export interface SupplierSavePayload {
  name: string
  contactPerson?: string
  phone?: string
  address?: string
  email?: string
  bankName?: string
  bankAccount?: string
  taxNo?: string
  settleType: string
  creditDays?: number
  leadTimeDays: number
  remark?: string
}

export function pageSuppliers(params: SupplierQuery) {
  return http.get<PageResult<SupplierListVO>>('/suppliers', params)
}

export function getSupplierDetail(id: number) {
  return http.get<SupplierListVO>(`/suppliers/${id}`)
}

export function createSupplier(data: SupplierSavePayload) {
  return http.post<SupplierListVO>('/suppliers', data)
}

export function updateSupplier(id: number, data: SupplierSavePayload) {
  return http.put<void>(`/suppliers/${id}`, data)
}

/** 停用/启用（FR-SUP-03）：停用后不可被新采购单选择，历史单据与结算记录保留可查 */
export function updateSupplierStatus(id: number, status: number) {
  return http.put<void>(`/suppliers/${id}/status`, { status })
}

/** 结算方式（字典 settle_type） */
export const SETTLE_TYPE_MAP: Record<string, string> = {
  CASH: '现结',
  MONTHLY: '月结',
  CREDIT: '账期',
}

// ---------- 供货商品（FR-SUP-04） ----------

/** 供应商供货商品行：含商品编码/名称/规格/分类/单位 */
export interface SupplierGoodsVO {
  id: number
  goodsId: number
  goodsCode: string
  goodsName: string
  spec: string | null
  categoryName: string | null
  unitName: string | null
  supplyPrice: number | null
  /** 是否默认供应商：1是 0否（一商品至多一个默认） */
  isDefault: number
  lastSupplyTime: string | null
  /** 最近供货价（最近一次入库实收单价，FR-SUP-04） */
  lastSupplyPrice: number | null
  /** 商品状态：1启用 0停用 */
  goodsStatus: number | null
}

export interface SupplierGoodsItemPayload {
  goodsId: number
  supplyPrice?: number
  isDefault: number
}

export function getSupplierGoods(id: number) {
  return http.get<SupplierGoodsVO[]>(`/suppliers/${id}/goods`)
}

/** 维护供货商品（FR-SUP-04）：全量覆盖保存 */
export function saveSupplierGoods(id: number, items: SupplierGoodsItemPayload[]) {
  return http.put<void>(`/suppliers/${id}/goods`, { items })
}

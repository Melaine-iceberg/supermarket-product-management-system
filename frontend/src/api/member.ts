import { http } from './request'
import type { PageResult } from './request'

// ---------- 会员档案（FR-MEM-01~04，07/08） ----------

/** 会员列表/详情视图（FR-MEM-03）：含等级名称与业务统计 */
export interface MemberListVO {
  id: number
  /** 会员编号：M+8位流水（BR-MEM-02） */
  memberNo: string
  name: string
  /** 手机号（无完整查看权限时后端返回脱敏 138****5678，BR-MEM-08） */
  phone: string
  /** 性别：1男 2女 */
  gender: number | null
  birthday: string | null
  address: string | null
  levelId: number
  levelName: string | null
  /** 当前积分 */
  points: number
  /** 储值余额 */
  balance: number
  /** 累计消费额 = Σ实付 − Σ退款（BR-MEM-06） */
  totalConsume: number
  /** 消费次数 */
  consumeTimes: number
  lastConsumeTime: string | null
  /** 注册人（收银员） */
  registerName: string | null
  registerTime: string | null
  /** 状态：1正常 0停用 */
  status: number
  remark: string | null
}

export interface MemberQuery {
  memberNo?: string
  name?: string
  phone?: string
  levelId?: number
  status?: number
  beginDate?: string
  endDate?: string
  page: number
  size: number
}

/** 注册会员（FR-MEM-01）：姓名+手机号必填，编号自动生成，初始普通会员 */
export interface MemberCreatePayload {
  name: string
  /** 大陆 11 位手机号，全局唯一（BR-MEM-01） */
  phone: string
  gender?: number
  birthday?: string
  address?: string
  remark?: string
}

/** 编辑会员档案（FR-MEM-02）：积分/储值/累计消费不可手工改 */
export interface MemberUpdatePayload {
  name: string
  phone: string
  gender?: number
  birthday?: string
  address?: string
  remark?: string
}

export function pageMembers(params: MemberQuery) {
  return http.get<PageResult<MemberListVO>>('/members', params)
}

export function getMemberDetail(id: number) {
  return http.get<MemberListVO>(`/members/${id}`)
}

export function createMember(data: MemberCreatePayload) {
  return http.post<MemberListVO>('/members', data)
}

export function updateMember(id: number, data: MemberUpdatePayload) {
  return http.put<void>(`/members/${id}`, data)
}

/** 停用/启用（BR-MEM-03）：停用后不享受会员价与折扣，档案保留 */
export function updateMemberStatus(id: number, status: number) {
  return http.put<void>(`/members/${id}/status`, { status })
}

// ---------- 储值充值（FR-MEM-08，店长授权） ----------

/** 充值收款方式：CASH现金 / BANK银行卡 / MOBILE移动支付 */
export type RechargePayType = 'CASH' | 'BANK' | 'MOBILE'

export const RECHARGE_PAY_MAP: Record<string, string> = {
  CASH: '现金',
  BANK: '银行卡',
  MOBILE: '移动支付',
}

export interface RechargePayload {
  amount: number
  payType: RechargePayType
  /** 店长授权人姓名（须 ≠ 发起人，FR-MEM-08） */
  authorizerName: string
  remark?: string
}

/** 储值充值：返回充值后余额 */
export function rechargeMember(id: number, data: RechargePayload) {
  return http.post<number>(`/members/${id}/recharge`, data, 30000)
}

// ---------- 积分流水（FR-MEM-07） ----------

/** 积分变动类型：CONSUME消费 / RETURN退货 / MANUAL人工调整 */
export const POINT_CHANGE_MAP: Record<string, { label: string; type: 'primary' | 'warning' | 'info' }> = {
  CONSUME: { label: '消费', type: 'primary' },
  RETURN: { label: '退货', type: 'warning' },
  MANUAL: { label: '人工调整', type: 'info' },
}

export interface PointRecordVO {
  id: number
  memberId: number
  changeType: string
  /** 变动值（消费+，退货-） */
  changeValue: number
  /** 变动后积分余额 */
  afterValue: number
  /** 来源单据号（销售单/退货单） */
  bizNo: string | null
  operatorName: string | null
  createTime: string
  remark: string | null
}

export function getPointRecords(id: number, page: number, size: number) {
  return http.get<PageResult<PointRecordVO>>(`/members/${id}/points`, { page, size })
}

// ---------- 储值流水（FR-MEM-08） ----------

/** 储值变动类型：RECHARGE充值 / CONSUME消费 / REFUND退款回充 / MANUAL人工调整 */
export const BALANCE_CHANGE_MAP: Record<string, { label: string; type: 'success' | 'primary' | 'warning' | 'info' }> = {
  RECHARGE: { label: '充值', type: 'success' },
  CONSUME: { label: '消费', type: 'primary' },
  REFUND: { label: '退款回充', type: 'warning' },
  MANUAL: { label: '人工调整', type: 'info' },
}

export interface BalanceRecordVO {
  id: number
  memberId: number
  changeType: string
  /** 变动金额（充值+，消费-） */
  changeAmount: number
  /** 变动后余额（不可为负，BR-MEM-07） */
  afterBalance: number
  /** 收款方式（充值时） */
  payType: string | null
  bizNo: string | null
  operatorName: string | null
  /** 授权人（充值需店长授权） */
  authorizerName: string | null
  createTime: string
  remark: string | null
}

export function getBalanceRecords(id: number, page: number, size: number) {
  return http.get<PageResult<BalanceRecordVO>>(`/members/${id}/balances`, { page, size })
}

// ---------- 消费记录（FR-MEM-04） ----------

/** 单条消费记录：销售单与退货单合并，时间倒序 */
export interface MemberConsumeVO {
  /** SALE销售单 / RETURN退货单 */
  type: string
  orderNo: string
  time: string
  /** 商品总额（退货单为 0） */
  goodsAmount: number
  discountAmount: number
  /** 实付金额（退货单为退款金额） */
  amount: number
  /** 积分变动（消费+ / 退货-） */
  pointsChange: number | null
  cashierName: string | null
}

/** 区间统计：消费总额（Σ实付−Σ退款）、频次、客单价 */
export interface ConsumeStat {
  totalAmount: number
  orderCount: number
  avgAmount: number
}

export interface ConsumeResult {
  records: PageResult<MemberConsumeVO>
  stat: ConsumeStat
}

export function getConsumeRecords(
  id: number,
  params: { beginDate?: string; endDate?: string; page: number; size: number },
) {
  return http.get<ConsumeResult>(`/members/${id}/consume-records`, params)
}

// ---------- Excel 批量导入（FR-MEM-03，校验—确认—部分提交） ----------

/** 导入单行校验结果 */
export interface MemberImportRowVO {
  /** Excel 行号（从 1 开始） */
  rowNum: number
  passed: boolean
  errors: string[]
  data: Record<string, string>
}

export interface MemberImportReportVO {
  total: number
  passedCount: number
  failedCount: number
  rows: MemberImportRowVO[]
}

export interface MemberImportResultVO {
  imported: number
  failed: number
  failedRows: MemberImportRowVO[]
}

/** 下载导入模板 */
export function downloadMemberTemplate() {
  return http.download('/members/import/template')
}

/** 上传校验：全量校验生成报告，不写库 */
export function validateMemberImport(file: File) {
  return http.upload<MemberImportReportVO>('/members/import/validate', file)
}

/** 确认提交：单事务内仅导入通过行，失败行返回明细供修正重传 */
export function commitMemberImport(file: File) {
  return http.upload<MemberImportResultVO>('/members/import/commit', file)
}

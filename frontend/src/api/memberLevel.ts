import { http } from './request'

// ---------- 会员等级规则（FR-MEM-05） ----------

/** 等级规则：名称、升级门槛（累计消费额）、折扣率、积分倍率、排序、状态 */
export interface MemberLevelVO {
  id: number
  name: string
  /** 升级门槛（累计消费额，BR-MEM-06 判定依据） */
  threshold: number
  /** 折扣率（如 0.98 = 98 折，BR-SALE-10） */
  discountRate: number
  /** 积分倍率（BR-MEM-05） */
  pointRate: number
  sortNo: number
  /** 状态：1启用 0停用（停用后折扣不再生效） */
  status: number
  createBy: string | null
  createTime: string | null
  updateBy: string | null
  updateTime: string | null
}

/** 等级保存请求（FR-MEM-05） */
export interface LevelSavePayload {
  name: string
  threshold: number
  /** (0,1]，如 0.98 = 98 折 */
  discountRate: number
  /** (0,99]，如 1.2 */
  pointRate: number
  sortNo: number
  status: number
}

/** 全部等级（按排序升序，含停用） */
export function listLevels() {
  return http.get<MemberLevelVO[]>('/member-levels')
}

export function createLevel(data: LevelSavePayload) {
  return http.post<void>('/member-levels', data)
}

/** 编辑等级规则：门槛与折扣率即时生效于后续结算（BR-MEM-06 / BR-SALE-10） */
export function updateLevel(id: number, data: LevelSavePayload) {
  return http.put<void>(`/member-levels/${id}`, data)
}

/** 停用/启用：停用后该等级折扣不再生效（收银按无折扣处理） */
export function updateLevelStatus(id: number, status: number) {
  return http.put<void>(`/member-levels/${id}/status/${status}`)
}

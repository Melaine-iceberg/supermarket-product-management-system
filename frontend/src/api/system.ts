import { http, TOKEN_KEY } from './request'
import { runExportTask } from './task'
import type { PageResult } from './request'
import type { MenuNode } from './auth'

// ---------- 用户管理（FR-SYS-02） ----------
export interface SysUserVO {
  id: number
  username: string
  realName: string
  phone: string | null
  status: number
  remark: string | null
  roleIds: number[]
  roleNames: string[]
  lastLoginTime: string | null
  mustChangePwd: boolean
  createTime: string | null
}

export interface UserCreated {
  user: SysUserVO
  initialPassword: string
}

export function pageUsers(params: {
  username?: string
  status?: number | string
  page: number
  size: number
}) {
  return http.get<PageResult<SysUserVO>>('/users', params)
}

export function createUser(data: {
  username: string
  realName: string
  phone?: string
  remark?: string
  roleIds: number[]
}) {
  return http.post<UserCreated>('/users', data)
}

export function updateUser(
  id: number,
  data: { realName: string; phone?: string; remark?: string; roleIds?: number[] },
) {
  return http.put<void>(`/users/${id}`, data)
}

export function updateUserStatus(id: number, status: number) {
  return http.put<void>(`/users/${id}/status`, { status })
}

/** 重置密码：password 为当前登录账号密码二次确认（BR-SYS-06） */
export function resetUserPassword(id: number, password: string) {
  return http.put<string>(`/users/${id}/reset-password`, { password })
}

// ---------- 角色管理（FR-SYS-04 / FR-SYS-05） ----------
export interface SysRoleVO {
  id: number
  roleName: string
  roleCode: string
  description: string | null
  builtin: boolean
  status: number
  createTime: string | null
}

export function listRoles() {
  return http.get<SysRoleVO[]>('/roles')
}

export function createRole(data: { roleName: string; roleCode: string; description?: string }) {
  return http.post<void>('/roles', data)
}

export function updateRole(
  id: number,
  data: { roleName: string; roleCode: string; description?: string },
) {
  return http.put<void>(`/roles/${id}`, data)
}

export function updateRoleStatus(id: number, status: number) {
  return http.put<void>(`/roles/${id}/status?status=${status}`)
}

export function getRolePerms(id: number) {
  return http.get<number[]>(`/roles/${id}/permissions`)
}

export function assignRolePerms(id: number, permIds: number[]) {
  return http.put<void>(`/roles/${id}/permissions`, { permIds })
}

/** 完整权限树（角色分配界面） */
export function getPermissionTree() {
  return http.get<MenuNode[]>('/permissions/tree')
}

// ---------- 系统参数（FR-SYS-06） ----------
export interface SysConfigVO {
  id: number
  configName: string
  configKey: string
  configValue: string
  valueType: 'STRING' | 'NUMBER' | 'BOOLEAN' | 'TIME'
  configGroup: string
  editable: number
  instantEffect: number
  remark: string | null
  updateTime: string | null
}

export function listConfigs() {
  return http.get<SysConfigVO[]>('/configs')
}

/** 修改参数：password 为当前登录账号密码二次确认（BR-SYS-06） */
export function updateConfig(key: string, configValue: string, remark?: string, password?: string) {
  return http.put<void>(`/configs/${key}`, { configValue, remark, password })
}

/** 打印页眉参数（IR-UI-08）：任意已登录用户可读，收银员无 sys:config:list 也能打印小票 */
export interface PrintInfoVO {
  storeName: string
  storeAddress: string
  storePhone: string
  /** 小票纸宽，仅 58 / 80 */
  receiptPaperWidth: 58 | 80
}

export function getPrintInfo() {
  return http.get<PrintInfoVO>('/configs/print-info')
}

/** 前端业务参数（BR-SALE-05/10、FR-SALE-11）：与后端校验同源，避免前端硬编码 */
export interface BusinessParamsVO {
  /** 整单折扣率下限（如 0.7，BR-SALE-10） */
  minDiscountRate: number
  /** 退款金额超过该阈值须店长授权（元，FR-SALE-11） */
  returnAuthAmount: number
  /** 退货有效期（天，BR-SALE-05） */
  returnValidDays: number
  /** 无结束符扫码枪：≤50ms 间隔判定扫码结束后自动查询（IR-HW-01③） */
  barcodeAutoQuery: boolean
}

/** 折扣下限/退货阈值/退货有效期（任意已登录用户可读） */
export function getBusinessParams() {
  return http.get<BusinessParamsVO>('/configs/business-params')
}

// ---------- 数据字典（FR-SYS-07） ----------
export interface DictVO {
  id: number
  dictType: string
  dictName: string
  remark: string | null
  items: {
    id: number
    label: string
    value: string
    sortNo: number
    status: number
    remark: string | null
  }[]
}

export function listDicts() {
  return http.get<DictVO[]>('/dicts')
}

/** 启用中的字典项（任意已登录用户）：业务表单选项，停用项不可被新单据选择（FR-SYS-07） */
export function getDictItems(dictType: string) {
  return http.get<DictVO['items']>(`/dicts/${dictType}/items`)
}

export function addDictItem(
  dictType: string,
  data: { label: string; value: string; sortNo: number; status: number; remark?: string },
) {
  return http.post<void>(`/dicts/${dictType}/items`, data)
}

export function updateDictItem(
  itemId: number,
  data: { label: string; value: string; sortNo: number; status: number; remark?: string },
) {
  return http.put<void>(`/dicts/items/${itemId}`, data)
}

// ---------- 操作日志（FR-SYS-08） ----------
export interface OperationLogVO {
  id: number
  userId: number | null
  username: string | null
  /** 操作人真实姓名（BR-SYS-07，GAP-P1-10） */
  realName: string | null
  roleNames: string | null
  module: string
  action: string
  target: string | null
  httpMethod: string | null
  requestUrl: string | null
  paramSummary: string | null
  ip: string | null
  userAgent: string | null
  result: number
  errorMsg: string | null
  costMs: number | null
  operateTime: string
}

export function pageOpLogs(params: {
  username?: string
  module?: string
  result?: number | string
  beginDate?: string
  endDate?: string
  page: number
  size: number
}) {
  return http.get<PageResult<OperationLogVO>>('/oplogs', params)
}

export function listOpLogModules() {
  return http.get<string[]>('/oplogs/modules')
}

export function exportOpLogs(params: {
  username?: string
  module?: string
  result?: number | string
  beginDate?: string
  endDate?: string
}) {
  // 异步任务：提交后轮询进度，完成后返回结果文件
  return runExportTask('/oplogs/export', params)
}

// ---------- 登录日志（FR-SYS-01） ----------
export interface LoginLogVO {
  id: number
  username: string
  userId: number | null
  ip: string | null
  browser: string | null
  os: string | null
  status: number
  message: string | null
  loginTime: string
}

export function pageLoginLogs(params: {
  username?: string
  status?: number | string
  beginDate?: string
  endDate?: string
  page: number
  size: number
}) {
  return http.get<PageResult<LoginLogVO>>('/loginlogs', params)
}

/** 当前登录用户的 Token（供需要直连的场景） */
export function currentToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

// ---------- 定时任务（IR-SW-06 / GAP-P1-03） ----------

/** 任务一览：定义 + cron + 最近一次执行结果 */
export interface ScheduledTaskVO {
  code: string
  name: string
  description: string
  cron: string
  lastStatus: 'SUCCESS' | 'FAILED' | null
  lastStartedAt: string | null
  lastDurationMs: number | null
  lastResultSummary: string | null
  lastError: string | null
}

/** 任务执行日志（定时触发与手工补跑共用） */
export interface ScheduledTaskLogVO {
  id: number
  taskCode: string
  taskName: string
  triggerType: 'SCHEDULED' | 'MANUAL'
  triggerBy: string | null
  status: 'SUCCESS' | 'FAILED'
  startedAt: string
  finishedAt: string
  durationMs: number
  resultSummary: string | null
  errorMessage: string | null
  retryCount: number
}

export function listScheduledTasks() {
  return http.get<ScheduledTaskVO[]>('/system/scheduled-tasks')
}

/** 手工触发补跑（任务幂等；返回本次执行日志） */
export function runScheduledTask(code: string) {
  return http.post<ScheduledTaskLogVO>(`/system/scheduled-tasks/${code}/run`)
}

export function pageScheduledTaskLogs(params: {
  taskCode?: string
  status?: string
  page: number
  size: number
}) {
  return http.get<PageResult<ScheduledTaskLogVO>>('/system/scheduled-tasks/logs', params)
}

// ---------- 数据备份（FR-SYS-10 / GAP-P2-23） ----------

export interface BackupRecordVO {
  id: number
  fileName: string
  filePath: string
  fileSize: number
  triggerType: 'SCHEDULED' | 'MANUAL'
  triggerBy: string | null
  status: 'SUCCESS' | 'FAILED'
  startedAt: string
  finishedAt: string | null
  durationMs: number | null
  errorMessage: string | null
  createTime: string
}

export function pageBackupRecords(params: { page: number; size: number }) {
  return http.get<PageResult<BackupRecordVO>>('/system/backups', params)
}

/** 一键触发全量备份（仅超级管理员；耗时取决于数据量） */
export function triggerBackup() {
  return http.post<BackupRecordVO>('/system/backups', undefined, 30 * 60 * 1000)
}

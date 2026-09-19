import { http, TOKEN_KEY } from './request'
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

export function resetUserPassword(id: number) {
  return http.put<string>(`/users/${id}/reset-password`)
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

export function updateConfig(key: string, configValue: string, remark?: string) {
  return http.put<void>(`/configs/${key}`, { configValue, remark })
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
  return http.download('/oplogs/export', params)
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

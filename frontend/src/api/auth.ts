import { http } from './request'

/** 当前登录用户信息 */
export interface UserInfo {
  id: number
  username: string
  realName: string
  roleCodes: string[]
  roleNames: string[]
  permCodes: string[]
}

/** 动态菜单节点（FR-SYS-05） */
export interface MenuNode {
  id: number
  parentId: number
  name: string
  permType: 'D' | 'M' | 'B'
  permCode: string | null
  routePath: string | null
  component: string | null
  icon: string | null
  sortNo: number
  children: MenuNode[]
}

export interface LoginResult {
  token: string
  expiresInSeconds: number
  mustChangePwd: boolean
  user: UserInfo
  menus: MenuNode[]
}

export interface MeResult {
  mustChangePwd: boolean
  user: UserInfo
  menus: MenuNode[]
}

export interface LoginForm {
  username: string
  password: string
  rememberMe: boolean
}

export function login(form: LoginForm) {
  return http.post<LoginResult>('/auth/login', form)
}

export function logout() {
  return http.post<void>('/auth/logout')
}

export function fetchMe() {
  return http.get<MeResult>('/auth/me')
}

export function fetchStoreName() {
  return http.get<string>('/auth/store-name')
}

export function changePassword(oldPassword: string, newPassword: string) {
  return http.put<void>('/auth/password', { oldPassword, newPassword })
}

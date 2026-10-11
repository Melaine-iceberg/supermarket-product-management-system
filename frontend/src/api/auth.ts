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

/** 登录渠道（V1.2） */
export type CodeChannel = 'sms' | 'email'
/** 验证码用途 */
export type CodeScene = 'login' | 'register'

/** 当前开放的登录方式（后端根据配置下发，登录页据此渲染） */
export interface LoginMethods {
  password: boolean
  phone: boolean
  email: boolean
  wechat: boolean
  register: boolean
  wechatAppId: string | null
  wechatScope: string
  codeChannels: CodeChannel[]
}

export interface CodeLoginForm {
  loginType: CodeChannel
  phone?: string
  email?: string
  code: string
  rememberMe: boolean
}

export interface SendCodeForm {
  channel: CodeChannel
  phone?: string
  email?: string
  scene: CodeScene
}

export interface SendCodeResult {
  expireMinutes: number
  resendAfterSeconds: number
  /** 仅本地开发（smms.login.expose-code-in-response=true）时返回 */
  devCode?: string
}

export interface RegisterForm {
  username: string
  password: string
  realName?: string
  phone?: string
  email?: string
  code: string
}

export interface WechatAuthUrl {
  authUrl: string
  state: string
}

export interface LoginForm {
  username: string
  password: string
  rememberMe: boolean
}

export function login(form: LoginForm) {
  return http.post<LoginResult>('/auth/login', form)
}

/** 用户当前开放的登录方式（未登录可访问） */
export function fetchLoginMethods() {
  return http.get<LoginMethods>('/auth/login-methods')
}

/** 发送短信/邮件验证码 */
export function sendAuthCode(form: SendCodeForm) {
  return http.post<SendCodeResult>('/auth/code', form)
}

/** 手机号/邮箱 + 验证码登录 */
export function loginByCode(form: CodeLoginForm) {
  return http.post<LoginResult>('/auth/login-by-code', form)
}

/** 微信扫码地址 */
export function fetchWechatAuthUrl() {
  return http.get<WechatAuthUrl>('/auth/wechat/auth-url')
}

/** 微信回调换取登录态 */
export function loginByWechat(code: string, state: string) {
  return http.post<LoginResult>('/auth/wechat/login', { code, state })
}

/** 员工自助注册（注册成功即登录） */
export function register(form: RegisterForm) {
  return http.post<LoginResult>('/auth/register', form)
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

import { defineStore } from 'pinia'
import { TOKEN_KEY } from '@/api/request'
import {
  login as loginApi,
  logout as logoutApi,
  fetchMe,
  loginByCode as loginByCodeApi,
  loginByWechat as loginByWechatApi,
  register as registerApi,
  type LoginForm,
  type CodeLoginForm,
  type RegisterForm,
  type LoginResult,
  type UserInfo,
  type MenuNode,
} from '@/api/auth'

/** 认证状态（FR-SYS-01/05）：Token 持久化到 localStorage */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: null as UserInfo | null,
    menus: [] as MenuNode[],
    mustChangePwd: false,
  }),
  getters: {
    isLogged: (state) => !!state.token,
    /** 按钮级权限（BR-SYS-05：前端隐藏仅为体验优化，后端仍独立校验） */
    hasPerm: (state) => (perm: string) => state.user?.permCodes.includes(perm) ?? false,
  },
  actions: {
    async login(form: LoginForm) {
      this.applyLoginResult(await loginApi(form))
    },
    /** 手机号/邮箱 + 验证码登录（V1.2） */
    async loginByCode(form: CodeLoginForm) {
      this.applyLoginResult(await loginByCodeApi(form))
    },
    /** 微信扫码回调换取登录态（V1.2） */
    async loginByWechat(code: string, state: string) {
      this.applyLoginResult(await loginByWechatApi(code, state))
    },
    /** 员工自助注册，注册成功即登录（V1.2） */
    async register(form: RegisterForm) {
      this.applyLoginResult(await registerApi(form))
    },
    /** 各登录入口共用的写入逻辑：先落 Token，再应用用户信息 */
    applyLoginResult(result: LoginResult) {
      this.token = result.token
      localStorage.setItem(TOKEN_KEY, result.token)
      this.applyMe(result.user, result.menus, result.mustChangePwd)
    },
    async refreshMe() {
      const me = await fetchMe()
      this.applyMe(me.user, me.menus, me.mustChangePwd)
    },
    async logout() {
      try {
        await logoutApi()
      } finally {
        this.clear()
      }
    },
    applyMe(user: UserInfo, menus: MenuNode[], mustChangePwd: boolean) {
      this.user = user
      this.menus = menus
      this.mustChangePwd = mustChangePwd
    },
    clear() {
      this.token = ''
      this.user = null
      this.menus = []
      this.mustChangePwd = false
      localStorage.removeItem(TOKEN_KEY)
    },
  },
})

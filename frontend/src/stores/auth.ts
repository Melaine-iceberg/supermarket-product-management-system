import { defineStore } from 'pinia'
import { TOKEN_KEY } from '@/api/request'
import {
  login as loginApi,
  logout as logoutApi,
  fetchMe,
  type LoginForm,
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
      const result = await loginApi(form)
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

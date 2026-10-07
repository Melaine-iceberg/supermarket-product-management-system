import type { RouteLocationNormalized, RouteLocationRaw } from 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    /** 公开页面（无须登录） */
    public?: boolean
    /** 页面菜单权限（列表权限） */
    perm?: string
    /** 页面标题 */
    title?: string
  }
}

/** 登录页路由名 */
export const LOGIN_ROUTE = 'login'
/** 首页路由名 */
export const HOME_ROUTE = 'home'
/** 强制改密页路由名 */
export const CHANGE_PASSWORD_ROUTE = 'change-password'
/** 无权限页路径 */
export const FORBIDDEN_PATH = '/403'

/**
 * 守卫所需的认证状态。与 Pinia `useAuthStore` 的结构对齐，
 * 便于单测注入替身而无需启动 Vue 应用（见 guard.spec.ts）。
 */
export interface AuthState {
  /** 是否已持有 Token */
  isLogged: boolean
  /** 已登录用户信息；刷新页面后为 null，需靠 refreshMe 恢复 */
  user: unknown
  /** 是否处于"重置密码后强制修改"状态 */
  mustChangePwd: boolean
  /** 按钮/菜单级权限判定 */
  hasPerm: (perm: string) => boolean
  /** 拉取当前用户与菜单 */
  refreshMe: () => Promise<unknown>
  /** 清空本地登录态 */
  clear: () => void
}

/** 守卫判定所需的路由信息（取 RouteLocationNormalized 的子集） */
export type GuardTarget = Pick<RouteLocationNormalized, 'name' | 'meta' | 'fullPath'>

/**
 * 路由守卫判定（FR-SYS-01/03/05，BR-SYS-02/05）。
 *
 * 顺序：公开页 → 登录态 → 刷新后恢复用户 → 强制改密 → 菜单权限。
 * 返回 `true` 表示放行，否则返回重定向目标（vue-router 4 的守卫返回语义）。
 */
export async function resolveNavigation(
  auth: AuthState,
  to: GuardTarget,
): Promise<true | RouteLocationRaw> {
  // 1) 公开页：已登录访问登录页则回首页，其余直接放行
  if (to.meta.public) {
    if (to.name === LOGIN_ROUTE && auth.isLogged) return '/home'
    return true
  }

  // 2) 未登录：跳登录页并记录来源，登录成功后回跳
  if (!auth.isLogged) {
    return {
      name: LOGIN_ROUTE,
      query: to.fullPath === '/' ? {} : { redirect: to.fullPath },
    }
  }

  // 3) 刷新后内存态丢失：用 Token 拉回用户与菜单，失败则视为登录失效
  if (!auth.user) {
    try {
      await auth.refreshMe()
    } catch {
      auth.clear()
      return { name: LOGIN_ROUTE }
    }
  }

  // 4) 重置密码后强制改密，且改密页自身不得被拦截（否则死循环）
  if (auth.mustChangePwd && to.name !== CHANGE_PASSWORD_ROUTE) {
    return { name: CHANGE_PASSWORD_ROUTE }
  }

  // 5) 菜单权限校验（前端仅体验优化，后端 @RequirePerm 仍独立校验，BR-SYS-05）
  const perm = to.meta.perm
  if (perm && !auth.hasPerm(perm)) {
    return FORBIDDEN_PATH
  }

  return true
}

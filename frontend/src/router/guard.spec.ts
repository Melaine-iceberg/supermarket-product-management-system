import { describe, expect, it, vi } from 'vite-plus/test'
import type { AuthState, GuardTarget } from './guard'
import { resolveNavigation } from './guard'

/** 构造认证状态替身：只覆盖被测分支所需的字段 */
function stubAuth(overrides: Partial<AuthState> = {}): AuthState {
  return {
    isLogged: false,
    user: null,
    mustChangePwd: false,
    hasPerm: () => false,
    refreshMe: vi.fn().mockResolvedValue(undefined),
    clear: vi.fn(),
    ...overrides,
  }
}

/** 构造路由目标替身 */
function target(name: string, fullPath: string, meta: GuardTarget['meta'] = {}): GuardTarget {
  return { name, fullPath, meta }
}

describe('resolveNavigation 路由守卫', () => {
  describe('① 公开页面（meta.public）', () => {
    it('未登录可访问公开页', async () => {
      const auth = stubAuth()
      await expect(
        resolveNavigation(auth, target('login', '/login', { public: true })),
      ).resolves.toBe(true)
      // 公开页不得触发 refreshMe，避免登录页无谓请求
      expect(auth.refreshMe).not.toHaveBeenCalled()
    })

    it('已登录访问登录页 → 重定向首页', async () => {
      const auth = stubAuth({ isLogged: true, user: {} })
      await expect(
        resolveNavigation(auth, target('login', '/login', { public: true })),
      ).resolves.toBe('/home')
    })

    it('已登录访问 403/404 等公开页 → 放行（不误跳首页）', async () => {
      const auth = stubAuth({ isLogged: true, user: {} })
      for (const [name, path] of [
        ['forbidden', '/403'],
        ['not-found', '/404'],
      ]) {
        await expect(resolveNavigation(auth, target(name, path, { public: true }))).resolves.toBe(
          true,
        )
      }
    })
  })

  describe('② 未登录拦截与来源记录（FR-SYS-01）', () => {
    it('未登录访问受保护页 → 跳登录页并携带 redirect', async () => {
      const auth = stubAuth()
      await expect(resolveNavigation(auth, target('goods-list', '/goods/list'))).resolves.toEqual({
        name: 'login',
        query: { redirect: '/goods/list' },
      })
    })

    it('根路径不产生冗余 redirect 参数', async () => {
      const auth = stubAuth()
      await expect(resolveNavigation(auth, target('home', '/'))).resolves.toEqual({
        name: 'login',
        query: {},
      })
    })

    it('redirect 保留查询串（登录后可回到带筛选的列表页）', async () => {
      const auth = stubAuth()
      const to = target('goods-list', '/goods/list?page=2&keyword=%E5%A5%B6')
      await expect(resolveNavigation(auth, to)).resolves.toEqual({
        name: 'login',
        query: { redirect: '/goods/list?page=2&keyword=%E5%A5%B6' },
      })
    })
  })

  describe('③ 刷新后恢复用户态（FR-SYS-05）', () => {
    it('已登录但内存无用户 → 先 refreshMe 再放行', async () => {
      const auth = stubAuth({ isLogged: true, user: null })
      const order: string[] = []
      auth.refreshMe = vi.fn(async () => {
        order.push('refreshMe')
        auth.user = { id: 1 }
      })
      await expect(resolveNavigation(auth, target('home', '/home'))).resolves.toBe(true)
      expect(order).toEqual(['refreshMe'])
    })

    it('refreshMe 失败 → 清空登录态并跳登录页', async () => {
      const auth = stubAuth({
        isLogged: true,
        user: null,
        refreshMe: vi.fn().mockRejectedValue(new Error('401')),
      })
      await expect(resolveNavigation(auth, target('home', '/home'))).resolves.toEqual({
        name: 'login',
      })
      expect(auth.clear).toHaveBeenCalledTimes(1)
    })

    it('已有用户信息时不重复请求', async () => {
      const auth = stubAuth({ isLogged: true, user: { id: 1 } })
      await resolveNavigation(auth, target('home', '/home'))
      expect(auth.refreshMe).not.toHaveBeenCalled()
    })
  })

  describe('④ 重置密码后强制改密（BR-SYS-02）', () => {
    it('mustChangePwd 为真 → 任意其他页面重定向改密页', async () => {
      const auth = stubAuth({ isLogged: true, user: {}, mustChangePwd: true })
      await expect(resolveNavigation(auth, target('goods-list', '/goods/list'))).resolves.toEqual({
        name: 'change-password',
      })
    })

    it('改密页自身必须放行，否则死循环', async () => {
      const auth = stubAuth({ isLogged: true, user: {}, mustChangePwd: true })
      await expect(
        resolveNavigation(auth, target('change-password', '/change-password')),
      ).resolves.toBe(true)
    })

    it('强制改密优先于权限校验（无权限也不该看到 403，而是先去改密）', async () => {
      const auth = stubAuth({
        isLogged: true,
        user: {},
        mustChangePwd: true,
        hasPerm: () => false,
      })
      await expect(
        resolveNavigation(auth, target('user-list', '/system/user', { perm: 'sys:user:list' })),
      ).resolves.toEqual({ name: 'change-password' })
    })
  })

  describe('⑤ 菜单权限（FR-SYS-03，后端 BR-SYS-05 独立校验）', () => {
    it('无权限 → 跳 403', async () => {
      const auth = stubAuth({ isLogged: true, user: {}, hasPerm: () => false })
      await expect(
        resolveNavigation(auth, target('user-list', '/system/user', { perm: 'sys:user:list' })),
      ).resolves.toBe('/403')
    })

    it('有权限 → 放行', async () => {
      const auth = stubAuth({ isLogged: true, user: {}, hasPerm: (p) => p === 'sys:user:list' })
      await expect(
        resolveNavigation(auth, target('user-list', '/system/user', { perm: 'sys:user:list' })),
      ).resolves.toBe(true)
    })

    it('hasPerm 收到的是路由 meta.perm 原文', async () => {
      const hasPerm = vi.fn().mockReturnValue(true)
      const auth = stubAuth({ isLogged: true, user: {}, hasPerm })
      await resolveNavigation(auth, target('rpt', '/report/sales', { perm: 'sys:rpt:sales' }))
      expect(hasPerm).toHaveBeenCalledWith('sys:rpt:sales')
    })

    it('无 perm 的路由（如首页）不参与权限校验', async () => {
      const hasPerm = vi.fn().mockReturnValue(false)
      const auth = stubAuth({ isLogged: true, user: {}, hasPerm })
      await expect(resolveNavigation(auth, target('home', '/home'))).resolves.toBe(true)
      expect(hasPerm).not.toHaveBeenCalled()
    })
  })
})

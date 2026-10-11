import { beforeEach, describe, expect, it } from 'vite-plus/test'
import { createPinia, setActivePinia } from 'pinia'
import { TOKEN_KEY } from '@/api/request'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

/**
 * 接线（wiring）集成测试：验证 `router/index.ts` 中注册的 beforeEach
 * 确实把判定委托给 `resolveNavigation`，并真实读取 Pinia 认证状态。
 *
 * 纯判定逻辑的分支覆盖见 guard.spec.ts；本文件只关心"守卫是否挂上、状态是否接通"，
 * 因此每次导航都落到公开页（/login、/403），避免加载业务页面。
 */
describe('路由守卫接线（真实 router + Pinia store）', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    // 重置到公开页，避免用例间相互影响
    return router.replace('/403').catch(() => undefined)
  })

  it('未登录访问受保护页 → 真实路由落到登录页并带 redirect', async () => {
    await router.push('/goods/list')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/goods/list')
  })

  it('登录后访问有权限的页面 → 正常进入', async () => {
    const auth = useAuthStore()
    auth.token = 'test-token'
    localStorage.setItem(TOKEN_KEY, 'test-token')
    auth.user = { permCodes: ['sys:goods:list'] } as never

    await router.push('/goods/list')
    expect(router.currentRoute.value.path).toBe('/goods/list')
  })

  it('登录但无菜单权限 → 真实路由落到 403', async () => {
    const auth = useAuthStore()
    auth.token = 'test-token'
    auth.user = { permCodes: [] } as never

    await router.push('/system/user')
    expect(router.currentRoute.value.path).toBe('/403')
  })

  it('mustChangePwd 时 → 真实路由强制落到改密页', async () => {
    const auth = useAuthStore()
    auth.token = 'test-token'
    auth.user = { permCodes: ['sys:goods:list'] } as never
    auth.mustChangePwd = true

    await router.push('/goods/list')
    expect(router.currentRoute.value.name).toBe('change-password')
  })
})

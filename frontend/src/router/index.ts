import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

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

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: () => import('@/layouts/MainLayout.vue'),
      children: [
        {
          path: '',
          redirect: '/home',
        },
        {
          path: 'home',
          name: 'home',
          component: () => import('@/views/HomeView.vue'),
          meta: { title: '首页' },
        },
        {
          path: 'change-password',
          name: 'change-password',
          component: () => import('@/views/ChangePasswordView.vue'),
          meta: { title: '修改密码' },
        },
        // 系统管理（FR-SYS-02 ~ 08）
        {
          path: 'system/user',
          name: 'system-user',
          component: () => import('@/views/system/user/index.vue'),
          meta: { perm: 'sys:user:list', title: '用户管理' },
        },
        {
          path: 'system/role',
          name: 'system-role',
          component: () => import('@/views/system/role/index.vue'),
          meta: { perm: 'sys:role:list', title: '角色管理' },
        },
        {
          path: 'system/config',
          name: 'system-config',
          component: () => import('@/views/system/config/index.vue'),
          meta: { perm: 'sys:config:list', title: '系统参数' },
        },
        {
          path: 'system/dict',
          name: 'system-dict',
          component: () => import('@/views/system/dict/index.vue'),
          meta: { perm: 'sys:dict:list', title: '数据字典' },
        },
        {
          path: 'system/oplog',
          name: 'system-oplog',
          component: () => import('@/views/system/oplog/index.vue'),
          meta: { perm: 'sys:oplog:list', title: '操作日志' },
        },
        {
          path: 'system/loginlog',
          name: 'system-loginlog',
          component: () => import('@/views/system/loginlog/index.vue'),
          meta: { perm: 'sys:loginlog:list', title: '登录日志' },
        },
        // 商品管理（FR-CAT / FR-GOODS）
        {
          path: 'goods/category',
          name: 'goods-category',
          component: () => import('@/views/goods/category/index.vue'),
          meta: { perm: 'sys:cat:list', title: '分类管理' },
        },
      ],
    },
    {
      path: '/403',
      name: 'forbidden',
      component: () => import('@/views/error/ForbiddenView.vue'),
      meta: { public: true, title: '无权限' },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/error/NotFoundView.vue'),
      meta: { public: true, title: '页面不存在' },
    },
  ],
})

/** 路由守卫：登录态、强制改密、菜单权限（FR-SYS-01/03/05） */
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    // 已登录访问登录页 → 回首页
    if (to.name === 'login' && auth.isLogged) return '/home'
    return true
  }
  if (!auth.isLogged) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  // 刷新后恢复用户信息与菜单
  if (!auth.user) {
    try {
      await auth.refreshMe()
    } catch {
      auth.clear()
      return { name: 'login' }
    }
  }
  // 重置密码后强制修改（BR-SYS-02）
  if (auth.mustChangePwd && to.name !== 'change-password') {
    return { name: 'change-password' }
  }
  // 菜单权限校验（后端仍会独立校验，BR-SYS-05）
  const perm = to.meta.perm
  if (perm && !auth.hasPerm(perm)) {
    return '/403'
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · 超市商品管理系统` : '超市商品管理系统'
})

export default router

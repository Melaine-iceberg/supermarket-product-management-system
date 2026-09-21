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
        {
          path: 'goods/list',
          name: 'goods-list',
          component: () => import('@/views/goods/list/index.vue'),
          meta: { perm: 'sys:goods:list', title: '商品档案' },
        },
        {
          path: 'goods/unit',
          name: 'goods-unit',
          component: () => import('@/views/goods/unit/index.vue'),
          meta: { perm: 'sys:unit:list', title: '计量单位' },
        },
        {
          path: 'goods/brand',
          name: 'goods-brand',
          component: () => import('@/views/goods/brand/index.vue'),
          meta: { perm: 'sys:brand:list', title: '品牌管理' },
        },
        // 库存管理（FR-INV）
        {
          path: 'inventory/list',
          name: 'inventory-list',
          component: () => import('@/views/inventory/list/index.vue'),
          meta: { perm: 'sys:inv:list', title: '库存查询' },
        },
        {
          path: 'inventory/record',
          name: 'inventory-record',
          component: () => import('@/views/inventory/record/index.vue'),
          meta: { perm: 'sys:inv-record:list', title: '库存流水' },
        },
        {
          path: 'inventory/warning',
          name: 'inventory-warning',
          component: () => import('@/views/inventory/warning/index.vue'),
          meta: { perm: 'sys:inv-warning:list', title: '库存预警' },
        },
        {
          path: 'inventory/check',
          name: 'inventory-check',
          component: () => import('@/views/inventory/check/index.vue'),
          meta: { perm: 'sys:check:list', title: '盘点管理' },
        },
        // 销售管理（FR-SALE）
        {
          path: 'sale/cashier',
          name: 'sale-cashier',
          component: () => import('@/views/sale/cashier/index.vue'),
          meta: { perm: 'sys:cashier:enter', title: '收银台' },
        },
        {
          path: 'sale/order',
          name: 'sale-order',
          component: () => import('@/views/sale/order/index.vue'),
          meta: { perm: 'sys:so:list', title: '销售单' },
        },
        {
          path: 'sale/return',
          name: 'sale-return',
          component: () => import('@/views/sale/return/index.vue'),
          meta: { perm: 'sys:ret:list', title: '退货管理' },
        },
        {
          path: 'sale/shift',
          name: 'sale-shift',
          component: () => import('@/views/sale/shift/index.vue'),
          meta: { perm: 'sys:shift:list', title: '交班管理' },
        },
        // 会员管理（FR-MEM）
        {
          path: 'member/list',
          name: 'member-list',
          component: () => import('@/views/member/list/index.vue'),
          meta: { perm: 'sys:mem:list', title: '会员档案' },
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

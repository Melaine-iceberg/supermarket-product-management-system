import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { resolveNavigation } from './guard'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    // 收银台独立全屏布局（IR-UI-02 / GAP-P1-12）：不含管理端菜单，避免误操作
    {
      path: '/sale/cashier',
      name: 'sale-cashier',
      component: () => import('@/views/sale/cashier/index.vue'),
      meta: { perm: 'sys:cashier:enter', title: '收银台' },
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
        {
          path: 'system/scheduled-task',
          name: 'system-scheduled-task',
          component: () => import('@/views/system/scheduled-task/index.vue'),
          meta: { perm: 'sys:sched:list', title: '定时任务' },
        },
        {
          path: 'system/backup',
          name: 'system-backup',
          component: () => import('@/views/system/backup/index.vue'),
          meta: { perm: 'sys:backup:list', title: '数据备份' },
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
        {
          path: 'inventory/loss',
          name: 'inventory-loss',
          component: () => import('@/views/inventory/loss/index.vue'),
          meta: { perm: 'sys:loss:list', title: '报损报溢' },
        },
        {
          path: 'inventory/health',
          name: 'inventory-health',
          component: () => import('@/views/inventory/health/index.vue'),
          meta: { perm: 'sys:inv:health', title: '库存体检' },
        },
        // 销售管理（FR-SALE）
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
        {
          path: 'member/level',
          name: 'member-level',
          component: () => import('@/views/member/level/index.vue'),
          meta: { perm: 'sys:mem-level:list', title: '会员等级' },
        },
        // 采购管理（FR-SUP）
        {
          path: 'purchase/supplier',
          name: 'purchase-supplier',
          component: () => import('@/views/purchase/supplier/index.vue'),
          meta: { perm: 'sys:sup:list', title: '供应商' },
        },
        // 采购管理（FR-PUR）
        {
          path: 'purchase/order',
          name: 'purchase-order',
          component: () => import('@/views/purchase/order/index.vue'),
          meta: { perm: 'sys:pur:list', title: '采购单' },
        },
        {
          path: 'purchase/receipt',
          name: 'purchase-receipt',
          component: () => import('@/views/purchase/receipt/index.vue'),
          meta: { perm: 'sys:rcv:list', title: '入库单' },
        },
        {
          path: 'purchase/suggest',
          name: 'purchase-suggest',
          component: () => import('@/views/purchase/suggest/index.vue'),
          meta: { perm: 'sys:suggest:list', title: '建议采购' },
        },
        // 统计报表（FR-RPT）
        {
          path: 'report/dashboard',
          name: 'report-dashboard',
          component: () => import('@/views/report/dashboard/index.vue'),
          meta: { perm: 'sys:rpt:dashboard', title: '经营看板' },
        },
        {
          path: 'report/sales',
          name: 'report-sales',
          component: () => import('@/views/report/sales/index.vue'),
          meta: { perm: 'sys:rpt:sales', title: '销售报表' },
        },
        {
          path: 'report/goods',
          name: 'report-goods',
          component: () => import('@/views/report/goods/index.vue'),
          meta: { perm: 'sys:rpt:goods', title: '商品分析' },
        },
        {
          path: 'report/inventory',
          name: 'report-inventory',
          component: () => import('@/views/report/inventory/index.vue'),
          meta: { perm: 'sys:rpt:inventory', title: '库存报表' },
        },
        {
          path: 'report/profit',
          name: 'report-profit',
          component: () => import('@/views/report/profit/index.vue'),
          meta: { perm: 'sys:rpt:profit', title: '毛利分析' },
        },
        {
          path: 'report/purchase',
          name: 'report-purchase',
          component: () => import('@/views/report/purchase/index.vue'),
          meta: { perm: 'sys:rpt:purchase', title: '采购报表' },
        },
        {
          path: 'report/member',
          name: 'report-member',
          component: () => import('@/views/report/member/index.vue'),
          meta: { perm: 'sys:rpt:member', title: '会员报表' },
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

/** 路由守卫：登录态、强制改密、菜单权限（FR-SYS-01/03/05）；判定逻辑见 ./guard.ts */
router.beforeEach((to) => resolveNavigation(useAuthStore(), to))

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · 超市商品管理系统` : '超市商品管理系统'
})

export default router

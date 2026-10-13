import { fileURLToPath } from 'node:url'

/** E2E 目标地址：默认 docker compose 部署栈（Nginx 80 端口），可经 E2E_BASE_URL 覆盖 */
export const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost'

/** 登录态持久化目录（global-setup 生成，已 gitignore） */
export const AUTH_DIR = fileURLToPath(new URL('../.auth', import.meta.url))

/** 本地存储 Token 键名（与 src/api/request.ts 的 TOKEN_KEY 保持一致） */
export const TOKEN_KEY = 'smms_token'

/** 演示账号（db/init-data.sql） */
export const ACCOUNTS = {
  admin: {
    username: 'admin',
    password: 'Admin@2026',
    realName: '系统管理员',
    roleName: '超级管理员',
  },
  manager: { username: 'manager', password: 'Manager@2026', realName: '王店长', roleName: '店长' },
  purchaser: {
    username: 'purchaser',
    password: 'Buyer@2026',
    realName: '李采购',
    roleName: '采购员',
  },
  keeper: { username: 'keeper', password: 'Keeper@2026', realName: '赵库管', roleName: '库管员' },
  cashier: {
    username: 'cashier',
    password: 'Cashier@2026',
    realName: '陈收银',
    roleName: '收银员',
  },
} as const

export type Role = keyof typeof ACCOUNTS

/**
 * 需要预生成登录态的演示账号（其余账号暂无 E2E 用例；
 * 控制登录请求次数，避免连续运行触发 NFR-SEC-03 登录限速 20 次/分钟）。
 */
export const STATE_ROLES: Role[] = ['admin', 'keeper', 'cashier']

/**
 * E2E 幂等种子数据（global-setup 经真实 API 创建）：
 * - 收银员分类浏览（DEF-19）需要启用分类下的启用商品：使用种子分类「食品」(id=1) + 非称重商品；
 * - 库管员验收（DEF-18）需要「已审核待入库」采购单：由种子供应商 + 种子商品创建并提交审核。
 */
export const SEED = {
  supplierName: 'E2E种子供应商',
  goods: {
    name: 'E2E测试可乐',
    barcode: '200000000012',
    categoryId: 1,
    unitId: 1,
    spec: '500ml',
    purchasePrice: 2,
    retailPrice: 3.5,
  },
  purchaseQuantity: 10,
  remark: 'E2E 自动造数，请勿删除',
} as const

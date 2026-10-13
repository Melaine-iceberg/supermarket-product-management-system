import fs from 'node:fs'
import { chromium, request, type APIRequestContext } from '@playwright/test'
import {
  ACCOUNTS,
  AUTH_DIR,
  BASE_URL,
  SEED,
  STATE_ROLES,
  TOKEN_KEY,
  type Role,
} from './helpers/constants'
import { apiLogin, apiOk, type PageResult } from './helpers/api'

/**
 * E2E 全局准备（无副作用幂等）：
 * 1. 确认部署栈可达（不可达给出可操作提示）；
 * 2. 经真实 API 以 admin 身份补齐种子数据：供应商 → 商品 → 已审核待入库采购单；
 * 3. 为全部演示账号生成登录态文件（localStorage Token），用例免重复登录。
 *
 * 种子数据是「关键路径回归」的确定性前提：
 * - 收银员分类浏览（DEF-19）需要启用分类下的启用商品；
 * - 库管员验收（DEF-18）需要 APPROVED/PARTIAL 采购单出现在待入库选单。
 */

interface Supplier {
  id: number
  name: string
}

interface Goods {
  id: number
  name: string
  barcode: string
}

interface PurchaseOrder {
  id: number
  orderNo: string
  status: string
}

export default async function globalSetup(): Promise<void> {
  const api = await request.newContext({ baseURL: BASE_URL })
  try {
    await ensureStackReachable(api)
    const adminToken = await apiLogin(api, ACCOUNTS.admin.username, ACCOUNTS.admin.password)
    await ensureSeedData(api, adminToken)
    await saveStorageStates(api, adminToken)
  } finally {
    await api.dispose()
  }
}

/** 栈未启动时立即失败并给出操作指引，避免所有用例逐一超时 */
async function ensureStackReachable(api: APIRequestContext): Promise<void> {
  try {
    const res = await api.get('/api/v1/auth/store-name')
    if (!res.ok()) throw new Error(`HTTP ${res.status()}`)
  } catch (e) {
    throw new Error(
      `无法访问 ${BASE_URL}（${(e as Error).message}）。` +
        '请先启动部署栈：docker compose up -d --build（或经 E2E_BASE_URL 指定已部署环境）',
      { cause: e },
    )
  }
}

async function ensureSeedData(api: APIRequestContext, token: string): Promise<void> {
  const supplierId = await ensureSupplier(api, token)
  const goodsId = await ensureGoods(api, token)
  await ensureReceivablePurchaseOrder(api, token, supplierId, goodsId)
}

async function ensureSupplier(api: APIRequestContext, token: string): Promise<number> {
  const page = await apiOk<PageResult<Supplier>>(api, 'get', '/api/v1/suppliers', {
    token,
    params: { name: SEED.supplierName, size: 100 },
  })
  const found = page.list.find((s) => s.name === SEED.supplierName)
  if (found) return found.id
  const created = await apiOk<Supplier>(api, 'post', '/api/v1/suppliers', {
    token,
    data: {
      name: SEED.supplierName,
      contactPerson: 'E2E',
      phone: '13800001234',
      settleType: 'CASH',
      leadTimeDays: 3,
      remark: SEED.remark,
    },
  })
  return created.id
}

async function ensureGoods(api: APIRequestContext, token: string): Promise<number> {
  const page = await apiOk<PageResult<Goods>>(api, 'get', '/api/v1/goods', {
    token,
    params: { barcode: SEED.goods.barcode, size: 50 },
  })
  const found = page.list.find((g) => g.barcode === SEED.goods.barcode)
  if (found) return found.id
  const created = await apiOk<{ id: number }>(api, 'post', '/api/v1/goods', {
    token,
    data: {
      name: SEED.goods.name,
      categoryId: SEED.goods.categoryId,
      unitId: SEED.goods.unitId,
      spec: SEED.goods.spec,
      purchasePrice: SEED.goods.purchasePrice,
      retailPrice: SEED.goods.retailPrice,
      barcode: SEED.goods.barcode,
      isWeighed: 0,
      allowReturn: 1,
      initialStock: 0,
      remark: SEED.remark,
    },
  })
  return created.id
}

/** 保证种子供应商名下存在一张「已审核待入库」采购单（中断残留的草稿/待审单会被续跑） */
async function ensureReceivablePurchaseOrder(
  api: APIRequestContext,
  token: string,
  supplierId: number,
  goodsId: number,
): Promise<void> {
  const page = await apiOk<PageResult<PurchaseOrder>>(api, 'get', '/api/v1/purchase-orders', {
    token,
    params: { supplierId, size: 100 },
  })
  if (page.list.some((o) => o.status === 'APPROVED' || o.status === 'PARTIAL')) return

  const reusable = page.list.find((o) => o.status === 'DRAFT' || o.status === 'SUBMITTED')
  let orderId: number
  let status: string
  if (reusable) {
    orderId = reusable.id
    status = reusable.status
  } else {
    const created = await apiOk<PurchaseOrder>(api, 'post', '/api/v1/purchase-orders', {
      token,
      data: {
        supplierId,
        expectDate: new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10),
        remark: SEED.remark,
        items: [{ goodsId, quantity: SEED.purchaseQuantity, price: SEED.goods.purchasePrice }],
      },
    })
    orderId = created.id
    status = created.status
  }

  if (status === 'DRAFT') {
    await apiOk(api, 'put', `/api/v1/purchase-orders/${orderId}/submit`, { token })
    status = 'SUBMITTED'
  }
  if (status !== 'SUBMITTED') {
    throw new Error(`造数异常：采购单 ${orderId} 状态为 ${status}，无法提交审核`)
  }
  await apiOk(api, 'put', `/api/v1/purchase-orders/${orderId}/audit`, {
    token,
    data: { approved: true, password: ACCOUNTS.admin.password },
  })

  // 复核：待入库选单（DEF-18 回归点）必须可见
  const pickable = await apiOk<PageResult<PurchaseOrder>>(
    api,
    'get',
    '/api/v1/receipt-orders/pickable',
    {
      token,
      params: { size: 10 },
    },
  )
  if (pickable.total < 1) {
    throw new Error('造数异常：已审核采购单未出现在待入库选单（/receipt-orders/pickable）')
  }
}

/** 各演示账号经登录接口取 Token，写入浏览器 localStorage 并导出登录态（admin 复用造数 Token） */
async function saveStorageStates(api: APIRequestContext, adminToken: string): Promise<void> {
  fs.mkdirSync(AUTH_DIR, { recursive: true })
  const tokens: Partial<Record<Role, string>> = { admin: adminToken }
  for (const role of STATE_ROLES) {
    if (!tokens[role]) {
      tokens[role] = await apiLogin(api, ACCOUNTS[role].username, ACCOUNTS[role].password)
    }
  }

  const browser = await chromium.launch()
  try {
    for (const role of STATE_ROLES) {
      const context = await browser.newContext({ baseURL: BASE_URL })
      const page = await context.newPage()
      // 先落到同源公开页（登录页），再写入 Token
      await page.goto('/login')
      await page.evaluate(({ name, value }) => localStorage.setItem(name, value), {
        name: TOKEN_KEY,
        value: tokens[role] as string,
      })
      await context.storageState({ path: `${AUTH_DIR}/${role}.json` })
      await context.close()
    }
  } finally {
    await browser.close()
  }
}

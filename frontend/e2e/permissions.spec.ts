import { expect, test } from '@playwright/test'
import { ACCOUNTS } from './helpers/constants'
import { storageStateFor, tokenFor } from './helpers/auth'
import { apiRequest } from './helpers/api'

/**
 * 角色权限链（BR-SYS-05，AC-15；DEF-18/19 演示账号路径复核）：
 * 后端接口级鉴权（401/403/专属只读接口 200）+ 前端菜单与 403 页体验。
 */
test.describe('接口权限探针', () => {
  test('匿名 401；收银员/库管员越权 403；专属只读接口放行', async ({ request }) => {
    // 匿名访问受保护接口
    expect((await request.get('/api/v1/users')).status()).toBe(401)

    // 收银员：管理端/报表接口 403，收银专属只读接口 200（DEF-19）
    const cashierToken = tokenFor('cashier')
    expect(
      (await apiRequest(request, 'get', '/api/v1/report/dashboard', { token: cashierToken }))
        .status,
    ).toBe(403)
    expect(
      (await apiRequest(request, 'get', '/api/v1/users', { token: cashierToken })).status,
    ).toBe(403)
    expect(
      (await apiRequest(request, 'get', '/api/v1/goods', { token: cashierToken })).status,
    ).toBe(403)
    const cashierCategories = await apiRequest(request, 'get', '/api/v1/sales/cashier/categories', {
      token: cashierToken,
    })
    expect(cashierCategories.status).toBe(200)
    expect(cashierCategories.body.code).toBe(0)

    // 库管员：待入库选单放行（DEF-18），采购单管理/报表 403
    const keeperToken = tokenFor('keeper')
    const pickable = await apiRequest(request, 'get', '/api/v1/receipt-orders/pickable', {
      token: keeperToken,
      params: { size: 10 },
    })
    expect(pickable.status).toBe(200)
    expect(pickable.body.code).toBe(0)
    expect(
      (await apiRequest(request, 'get', '/api/v1/purchase-orders', { token: keeperToken })).status,
    ).toBe(403)
    expect(
      (await apiRequest(request, 'get', '/api/v1/report/dashboard', { token: keeperToken })).status,
    ).toBe(403)
  })
})

test.describe('收银员前端菜单与越权路由', () => {
  test.use({ storageState: storageStateFor('cashier') })

  test('菜单按权限渲染，直连管理路由落 403 页', async ({ page }) => {
    await page.goto('/home')
    await expect(page.locator('.user-info')).toContainText(ACCOUNTS.cashier.realName)

    const aside = page.locator('.aside')
    await expect(aside).toContainText('收银台')
    await expect(aside).not.toContainText('系统管理')
    await expect(aside).not.toContainText('经营看板')

    await page.goto('/system/user')
    await expect(page).toHaveURL((url) => url.pathname === '/403')
    await expect(page.getByText('403 无权访问')).toBeVisible()
  })
})

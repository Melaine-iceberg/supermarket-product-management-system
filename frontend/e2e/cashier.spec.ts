import { expect, test } from '@playwright/test'
import { SEED } from './helpers/constants'
import { storageStateFor } from './helpers/auth'
import { observeFailures, settleFailures } from './helpers/observe'

/**
 * 收银台分类树浏览点选（FR-SALE-02③，GAP-P0-06 / DEF-19 回归）：
 * 收银员经收银专用只读接口加载分类树与商品，点选加入购物车，全程无 403/5xx。
 */
test.describe('收银员分类树浏览', () => {
  test.use({ storageState: storageStateFor('cashier') })

  test('分类树加载、按分类浏览商品并加入明细（DEF-19）', async ({ page }) => {
    const collector = observeFailures(page)

    await page.goto('/sale/cashier')
    await expect(page.locator('.el-breadcrumb')).toContainText('收银台')

    // 打开「分类浏览」→ 分类树经 /sales/cashier/categories 加载（DEF-19 原 403 接口）
    const categoriesResponse = page.waitForResponse(
      (res) => res.url().includes('/api/v1/sales/cashier/categories') && res.status() === 200,
    )
    await page.getByRole('button', { name: '分类浏览' }).click()
    await categoriesResponse

    const dialog = page.getByRole('dialog', { name: '分类浏览选品' })
    await expect(dialog).toBeVisible()
    await expect(dialog.locator('.el-tree-node').first()).toBeVisible()

    // 点击「食品」分类 → 经 /sales/cashier/browse 加载分类商品
    const browseResponse = page.waitForResponse(
      (res) => res.url().includes('/api/v1/sales/cashier/browse') && res.status() === 200,
    )
    await dialog.getByText('食品', { exact: true }).click()
    await browseResponse

    // 种子商品可见并可点选加入购物车
    const row = dialog.locator('tr.el-table__row', { hasText: SEED.goods.name })
    await expect(row).toBeVisible()
    await row.click()
    await expect(page.locator('.el-message--success')).toContainText(`已加入：${SEED.goods.name}`)
    await expect(page.locator('.cart-table')).toContainText(SEED.goods.name)

    expect(await settleFailures(page, collector)).toEqual([])
  })
})

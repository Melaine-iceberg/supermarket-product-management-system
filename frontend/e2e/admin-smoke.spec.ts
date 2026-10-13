import { expect, test } from '@playwright/test'
import { storageStateFor, tokenFor } from './helpers/auth'
import { apiRequest } from './helpers/api'
import { observeFailures, settleFailures } from './helpers/observe'

/**
 * 管理端关键页面冒烟（AC-D01 / §9 全链路回归的自动化固化）：
 * 逐模块确认页面可加载、无 5xx/业务失败/页面异常，覆盖此前由人工截图验证的路径。
 */
test.describe('管理端关键页面冒烟', () => {
  test.use({ storageState: storageStateFor('admin') })

  test('首页：欢迎信息与快捷入口', async ({ page }) => {
    const collector = observeFailures(page)
    await page.goto('/home')
    await expect(page.getByRole('heading', { name: /系统管理员/ })).toBeVisible()
    await expect(page.getByText('快捷入口')).toBeVisible()
    await expect(page.getByText('系统说明')).toBeVisible()
    expect(await settleFailures(page, collector)).toEqual([])
  })

  test('商品与库存：商品档案、库存查询', async ({ page }) => {
    const collector = observeFailures(page)

    await page.goto('/goods/list')
    await expect(page.locator('.el-breadcrumb')).toContainText('商品档案')
    await expect(page.locator('.el-table').first()).toBeVisible()

    await page.goto('/inventory/list')
    await expect(page.locator('.el-breadcrumb')).toContainText('库存查询')
    await expect(page.locator('.el-table').first()).toBeVisible()

    expect(await settleFailures(page, collector)).toEqual([])
  })

  test('采购：采购单与建议采购页面', async ({ page }) => {
    const collector = observeFailures(page)

    await page.goto('/purchase/order')
    await expect(page.locator('.el-breadcrumb')).toContainText('采购单')
    await expect(page.locator('.el-table').first()).toBeVisible()

    await page.goto('/purchase/suggest')
    await expect(page.locator('.el-breadcrumb')).toContainText('建议采购')

    expect(await settleFailures(page, collector)).toEqual([])
  })

  test('报表：经营看板与销售报表（ECharts 渲染）', async ({ page }) => {
    const collector = observeFailures(page)

    await page.goto('/report/dashboard')
    await expect(page.locator('.el-breadcrumb')).toContainText('经营看板')
    await expect(page.locator('canvas').first()).toBeVisible()

    await page.goto('/report/sales')
    await expect(page.locator('.el-breadcrumb')).toContainText('销售报表')
    await expect(page.locator('canvas, .el-table').first()).toBeVisible()

    expect(await settleFailures(page, collector)).toEqual([])
  })

  test('系统管理：用户列表与操作日志（DEF-25 真实姓名列）', async ({ page }) => {
    const collector = observeFailures(page)

    await page.goto('/system/user')
    await expect(page.locator('.el-table').first()).toContainText('系统管理员')

    await page.goto('/system/oplog')
    await expect(page.locator('.el-breadcrumb')).toContainText('操作日志')
    await expect(page.getByRole('columnheader', { name: '真实姓名' })).toBeVisible()

    expect(await settleFailures(page, collector)).toEqual([])
  })

  test('历史缺陷回归探针：入库单/采购退货列表接口（DEF-02/03）', async ({ request }) => {
    const token = tokenFor('admin')
    const cases = [
      ['入库单列表', '/api/v1/receipt-orders?page=1&size=10'],
      ['采购退货列表', '/api/v1/purchase-returns?page=1&size=10'],
      ['采购单列表', '/api/v1/purchase-orders?page=1&size=10'],
    ] as const
    for (const [name, url] of cases) {
      const { status, body } = await apiRequest(request, 'get', url, { token })
      expect(status, `${name} HTTP 状态`).toBe(200)
      expect(body.code, `${name} 业务码`).toBe(0)
    }
  })
})

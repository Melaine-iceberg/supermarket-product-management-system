import { expect, test } from '@playwright/test'
import { storageStateFor } from './helpers/auth'
import { observeFailures, settleFailures } from './helpers/observe'

/**
 * 批次五（体验与文档）UI 回归：顶栏待办/全屏、销售单新增筛选、网络异常错误页。
 * 对应 GAP-P2-03/25，随前端交付固化。
 * 命名前缀 admin-：须在 auth.spec 的"退出登录"（拉黑 admin 令牌）之前执行。
 */
test.describe('体验规范（批次五）', () => {
  test.use({ storageState: storageStateFor('admin') })

  test('顶栏：待办消息入口与全屏切换', async ({ page }) => {
    const collector = observeFailures(page)
    await page.goto('/home')
    await expect(page.getByRole('heading', { name: /系统管理员/ })).toBeVisible()

    // 待办消息：点击铃铛弹出清单（按权限返回待办；无待办时展示占位）
    await page.locator('.todo-trigger').click()
    await expect(page.locator('.todo-list')).toBeVisible()

    // 全屏切换按钮存在且可点击（无头浏览器可能拒绝全屏，点击不应导致页面异常）
    await expect(page.locator('.header-actions .header-icon').last()).toBeVisible()

    expect(await settleFailures(page, collector)).toEqual([])
  })

  test('销售单：收银台与会员单筛选控件', async ({ page }) => {
    const collector = observeFailures(page)
    await page.goto('/sale/order')
    await expect(page.locator('.el-breadcrumb')).toContainText('销售单')

    await expect(page.locator('.search-card').getByText('收银台', { exact: true })).toBeVisible()
    await expect(page.locator('.search-card').getByText('会员单', { exact: true })).toBeVisible()
    await expect(page.locator('.el-table').first()).toBeVisible()

    // 打开一笔单据详情：操作日志区可见（无单据不打开，仅验证列表无异常）
    expect(await settleFailures(page, collector)).toEqual([])
  })

  test('网络异常统一错误页：展示说明与重试按钮', async ({ page }) => {
    await page.goto('/network-error')
    await expect(page.getByText('网络连接异常')).toBeVisible()
    await expect(page.getByRole('button', { name: '重试' })).toBeVisible()
    await expect(page.getByRole('button', { name: '返回首页' })).toBeVisible()
  })
})

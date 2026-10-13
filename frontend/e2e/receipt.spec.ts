import { expect, test } from '@playwright/test'
import { storageStateFor } from './helpers/auth'
import { observeFailures, settleFailures } from './helpers/observe'

/**
 * 库管员入库验收主流程（FR-PUR-06；GAP-P0-05 / DEF-18 回归）：
 * 库管员点击「新建验收」→ 待入库选单经 /receipt-orders/pickable 加载（原 403 接口）
 * → 打开验收录入弹窗（含商品明细与提交按钮）。只读验证，不提交，避免改变演示库状态。
 */
test.describe('库管员入库验收', () => {
  test.use({ storageState: storageStateFor('keeper') })

  test('待入库选单可选单并进入验收录入（DEF-18）', async ({ page }) => {
    const collector = observeFailures(page)

    await page.goto('/purchase/receipt')
    await expect(page.locator('.el-breadcrumb')).toContainText('入库单')

    // 新建验收 → 待入库采购单列表（DEF-18 原 403 接口）
    const pickableResponse = page.waitForResponse(
      (res) => res.url().includes('/api/v1/receipt-orders/pickable?') && res.status() === 200,
    )
    await page.getByRole('button', { name: '新建验收' }).click()
    await pickableResponse

    const pickDialog = page.getByRole('dialog', { name: '选择待入库采购单' })
    await expect(pickDialog).toBeVisible()
    const firstRow = pickDialog.locator('tr.el-table__row').first()
    await expect(firstRow).toBeVisible()
    const orderNo = (await firstRow.locator('td').first().innerText()).trim()

    // 选中一行点「验收」→ 取详情（/receipt-orders/pickable/{id}）并打开录入弹窗
    const detailResponse = page.waitForResponse(
      (res) => /\/api\/v1\/receipt-orders\/pickable\/\d+/.test(res.url()) && res.status() === 200,
    )
    await firstRow.getByRole('button', { name: '验收' }).click()
    await detailResponse

    const receiveDialog = page.getByRole('dialog', { name: `入库验收 ${orderNo}` })
    await expect(receiveDialog).toBeVisible()
    await expect(receiveDialog.locator('tr.el-table__row').first()).toBeVisible()
    await expect(receiveDialog.getByRole('button', { name: '提交验收' })).toBeVisible()

    expect(await settleFailures(page, collector)).toEqual([])
  })

  test('入库单列表可查询（DEF-02 列表映射回归）', async ({ page }) => {
    const collector = observeFailures(page)
    await page.goto('/purchase/receipt')
    // 列表接口异常会被 observeFailures 捕获；此处只验证页面与表格渲染（空库显示空态）
    await expect(
      page.locator('.el-table').first().locator('tr.el-table__row, .el-table__empty-block').first(),
    ).toBeVisible()
    expect(await settleFailures(page, collector)).toEqual([])
  })
})

import { expect, request, test } from '@playwright/test'
import { BASE_URL, SEED } from './helpers/constants'
import { storageStateFor, tokenFor } from './helpers/auth'
import { observeFailures, settleFailures } from './helpers/observe'

/**
 * 批次六（低成本高价值项收尾）UI 回归：
 * - 报表默认"今日"区间与统一快捷项（BR-RPT-04 / GAP-P2-21）；
 * - 商品条码占用提示商品名并可"重新生成编码"（UC-GOODS-001 E1/E2 / GAP-P2-08）；
 * - 收银台会员检索手机号仅显示后 4 位（BR-MEM-08 / GAP-P2-19）。
 * 命名前缀 admin-：须在 auth.spec 的"退出登录"（拉黑 admin 令牌）之前执行。
 */
test.describe('报表区间（批次六）', () => {
  test.use({ storageState: storageStateFor('admin') })

  test('报表默认"今日"且快捷项齐全（BR-RPT-04/GAP-P2-21）', async ({ page }) => {
    const collector = observeFailures(page)
    await page.goto('/report/goods')

    const presets = page.locator('.presets')
    await expect(presets).toBeVisible()
    for (const label of ['今日', '昨日', '本周', '本月', '上月', '近7日', '近30日']) {
      await expect(presets.getByRole('button', { name: label })).toBeVisible()
    }

    // 默认区间=今日（BR-RPT-04）：日期选择器首个输入框为当天
    const today = new Date()
    const ymd = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(
      today.getDate(),
    ).padStart(2, '0')}`
    await expect(page.locator('.el-date-editor input').first()).toHaveValue(ymd)

    expect(await settleFailures(page, collector)).toEqual([])
  })
})

test.describe('商品条码占用与重新生成编码（批次六）', () => {
  test.use({ storageState: storageStateFor('admin') })

  test('条码占用提示商品名；编码重复可重新生成（UC-GOODS-001 E1/E2）', async ({ page }) => {
    // 取种子商品的既有编码，用于触发 E2「编码重复」
    const api = await request.newContext({ baseURL: BASE_URL })
    let existingCode = ''
    try {
      const res = await api.get('/api/v1/goods', {
        params: { barcode: SEED.goods.barcode, size: 20 },
        headers: { Authorization: `Bearer ${tokenFor('admin')}` },
      })
      const body = (await res.json()) as { data: { list: { goodsCode: string }[] } }
      existingCode = body.data.list[0]?.goodsCode ?? ''
    } finally {
      await api.dispose()
    }
    expect(existingCode).not.toBe('')

    await page.goto('/goods/list')
    await page.getByRole('button', { name: '新增商品' }).click()
    const dialog = page.getByRole('dialog', { name: '新增商品' })
    await expect(dialog).toBeVisible()

    // E2 入口：商品编码"重新生成"按钮始终可用
    await expect(dialog.getByRole('button', { name: '重新生成' })).toBeVisible()

    // 填必填项（名称/分类/单位/零售价）与已占用条码
    await dialog.locator('.el-form-item', { hasText: '商品名称' }).locator('input').fill('E2E条码占用验证')
    await dialog.locator('.el-form-item', { hasText: '所属分类' }).locator('.el-select').click()
    await page.locator('.el-tree-node__content').filter({ hasText: /^食品$/ }).first().click()
    await dialog.locator('.el-form-item', { hasText: '计量单位' }).locator('.el-select').click()
    await page.locator('.el-select-dropdown__item:visible').first().click()
    await dialog.locator('.el-form-item', { hasText: '零售单价' }).locator('input').fill('9.9')
    await dialog.locator('.el-form-item', { hasText: '商品条码' }).locator('input').fill(SEED.goods.barcode)
    await dialog.getByRole('button', { name: '保存' }).click()

    // E1：拒绝保存并提示占用商品名称，条码字段高亮
    await expect(page.locator('.el-message--error').last()).toContainText(
      `条码 ${SEED.goods.barcode} 已被商品【${SEED.goods.name}】占用`,
    )
    await expect(dialog.locator('.barcode-error')).toBeVisible()

    // E2：换用未占用条码但手工编码重复 → 弹出"重新生成编码"，确认后清空手工编码
    await dialog.locator('.el-form-item', { hasText: '商品条码' }).locator('input').fill('7770000000000001')
    await dialog.locator('.el-form-item', { hasText: '商品编码' }).locator('input').fill(existingCode)
    await dialog.getByRole('button', { name: '保存' }).click()
    const codeDialog = page.getByRole('dialog', { name: '商品编码重复' })
    await expect(codeDialog).toBeVisible()
    await codeDialog.getByRole('button', { name: '重新生成编码' }).click()
    await expect(dialog.locator('.el-form-item', { hasText: '商品编码' }).locator('input')).toHaveValue('')
  })
})

test.describe('收银台会员手机号后 4 位（批次六）', () => {
  test.use({ storageState: storageStateFor('cashier') })

  test('会员检索仅显示后 4 位，不泄露前 3 位（BR-MEM-08/GAP-P2-19）', async ({ page }) => {
    const collector = observeFailures(page)
    await page.goto('/sale/cashier')
    await expect(page.locator('.cashier-page')).toBeVisible()

    await page.locator('.member-btn').click()
    const dialog = page.getByRole('dialog', { name: '会员识别' })
    await dialog.getByPlaceholder('手机号 / 会员编号 / 姓名').fill(SEED.member.phone)
    const searchResponse = page.waitForResponse(
      (res) => res.url().includes('/api/v1/sales/members') && res.status() === 200,
    )
    await dialog.getByRole('button', { name: '查询' }).click()
    await searchResponse

    const row = dialog.locator('tr.el-table__row', { hasText: SEED.member.name })
    await expect(row).toBeVisible()
    await expect(row).toContainText(`****${SEED.member.phone.slice(-4)}`)
    await expect(row).not.toContainText(SEED.member.phone.slice(0, 3))

    expect(await settleFailures(page, collector)).toEqual([])
  })
})

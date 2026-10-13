import { expect, test } from '@playwright/test'
import { ACCOUNTS } from './helpers/constants'
import { loginViaUi, storageStateFor, submitLogin } from './helpers/auth'

/**
 * 登录与登录态（FR-SYS-01，AC-01）：
 * 表单登录、错误提示、刷新保持、退出清理、未登录拦截与回跳。
 *
 * 说明：为控制登录请求次数（NFR-SEC-03 限速 20 次/分钟），
 * “刷新保持/退出”复用 global-setup 生成的登录态，仅核心路径走真实表单登录。
 */
test.describe('认证与登录', () => {
  test('未登录访问受保护页面：重定向登录页并保留回跳地址，登录后返回', async ({ page }) => {
    await page.goto('/system/user')
    await expect(page).toHaveURL(
      (url) => url.pathname === '/login' && url.searchParams.get('redirect') === '/system/user',
    )

    // 在带 redirect 的登录页上直接提交（不可重新 goto，否则查询串丢失）
    await submitLogin(page, 'admin')
    await expect(page).toHaveURL((url) => url.pathname === '/system/user')
    await expect(page.locator('.el-breadcrumb')).toContainText('用户管理')
  })

  test('admin 表单登录：进入首页并展示真实姓名与角色', async ({ page }) => {
    await loginViaUi(page, 'admin')
    await expect(page).toHaveURL((url) => url.pathname === '/home')
    await expect(page.getByRole('heading', { name: /系统管理员/ })).toBeVisible()
    await expect(page.locator('.user-info')).toContainText(ACCOUNTS.admin.realName)
    await expect(page.locator('.role-tag')).toContainText(ACCOUNTS.admin.roleName)
  })

  test('密码错误：停留登录页并提示统一错误信息', async ({ page }) => {
    await page.goto('/login')
    // 使用不存在的账号验证统一模糊提示，避免污染演示账号的失败计数
    await page.getByPlaceholder('用户名').fill('e2e_ghost_user')
    await page.getByPlaceholder('密码').fill('Wrong@2026')
    await page.getByRole('button', { name: '登 录' }).click()

    await expect(page.locator('.el-message--error')).toContainText('用户名或密码错误')
    await expect(page).toHaveURL((url) => url.pathname === '/login')
  })

  test.describe('登录态保持与退出（复用预生成登录态）', () => {
    test.use({ storageState: storageStateFor('admin') })

    test('刷新页面后登录态保持（Token 持久化 + /auth/me 恢复）', async ({ page }) => {
      await page.goto('/home')
      await expect(page.locator('.user-info')).toContainText(ACCOUNTS.admin.realName)
      await page.reload()
      await expect(page).toHaveURL((url) => url.pathname === '/home')
      await expect(page.locator('.user-info')).toContainText(ACCOUNTS.admin.realName)
    })

    test('退出登录：清除本地会话并再次拦截受保护页面', async ({ page }) => {
      await page.goto('/home')
      await expect(page.locator('.user-info')).toContainText(ACCOUNTS.admin.realName)
      await page.locator('.user-info').click()
      await page.locator('.el-dropdown-menu__item').filter({ hasText: '退出登录' }).click()

      await expect(page).toHaveURL((url) => url.pathname === '/login')
      expect(await page.evaluate(() => localStorage.getItem('smms_token'))).toBeNull()

      await page.goto('/home')
      await expect(page).toHaveURL(
        (url) => url.pathname === '/login' && url.searchParams.get('redirect') === '/home',
      )
    })
  })
})

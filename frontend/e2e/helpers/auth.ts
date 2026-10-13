import fs from 'node:fs'
import path from 'node:path'
import { type Page } from '@playwright/test'
import { ACCOUNTS, AUTH_DIR, BASE_URL, TOKEN_KEY, type Role } from './constants'

/** 演示账号登录态文件路径（global-setup 生成） */
export function storageStateFor(role: Role): string {
  return path.join(AUTH_DIR, `${role}.json`)
}

/** 从登录态文件读取 Token（API 探针复用，避免重复登录触发频率限制） */
export function tokenFor(role: Role): string {
  const raw = JSON.parse(fs.readFileSync(storageStateFor(role), 'utf-8')) as {
    origins: { origin: string; localStorage: { name: string; value: string }[] }[]
  }
  const origin = raw.origins.find((o) => o.origin === BASE_URL)
  const item = origin?.localStorage.find((i) => i.name === TOKEN_KEY)
  if (!item?.value) {
    throw new Error(`登录态文件缺少 Token：${storageStateFor(role)}（应由 global-setup 生成）`)
  }
  return item.value
}

/** 在已打开的登录页填写并提交表单（用于需保留 redirect 查询串等既有页面状态的场景） */
export async function submitLogin(page: Page, role: Role): Promise<void> {
  const account = ACCOUNTS[role]
  await page.getByPlaceholder('用户名').fill(account.username)
  await page.getByPlaceholder('密码').fill(account.password)
  await page.getByRole('button', { name: '登 录' }).click()
  await page.waitForURL((url) => !url.pathname.startsWith('/login'))
}

/** 表单登录（FR-SYS-01）：成功后等待离开登录页，落点由调用方断言 */
export async function loginViaUi(page: Page, role: Role): Promise<void> {
  await page.goto('/login')
  await submitLogin(page, role)
}

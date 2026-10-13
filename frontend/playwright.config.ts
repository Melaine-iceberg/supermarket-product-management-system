import { defineConfig, devices } from '@playwright/test'

/**
 * 浏览器 E2E 配置（Playwright + 无头 Chromium）。
 *
 * 目标环境：docker compose 部署栈（MySQL + 后端 + Nginx，默认 http://localhost），
 * 可用 E2E_BASE_URL 指向其它已部署环境（如公网隧道域名）。
 *
 * 运行：启动部署栈后，在 frontend 下执行 `pnpm test:e2e`；详细说明见 e2e/README.md。
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  outputDir: 'test-results',
  // 全部用例共享同一套演示数据与单实例后端，串行执行保证结果确定
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    actionTimeout: 10_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})

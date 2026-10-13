import type { Page, Response } from '@playwright/test'

export interface FailureCollector {
  /** 尚未结算的响应体检查 */
  pending: Promise<void>[]
  /** 已发现的失败信号 */
  failures: string[]
}

/**
 * 收集页面运行期间的失败信号：
 * - 未捕获的页面异常（pageerror）
 * - API 5xx
 * - HTTP 200 但业务码非 0（IR-SW-01）
 */
export function observeFailures(page: Page): FailureCollector {
  const failures: string[] = []
  const pending: Promise<void>[] = []

  page.on('pageerror', (e) => failures.push(`页面异常：${e.message}`))
  page.on('response', (res) => pending.push(inspect(res)))

  async function inspect(res: Response): Promise<void> {
    const url = res.url()
    if (!url.includes('/api/v1/')) return
    if (res.status() >= 500) {
      failures.push(`HTTP ${res.status()} ${url}`)
      return
    }
    const contentType = res.headers()['content-type'] ?? ''
    if (res.status() === 200 && contentType.includes('json')) {
      try {
        const body = (await res.json()) as { code?: number; message?: string }
        if (typeof body.code === 'number' && body.code !== 0) {
          failures.push(`业务失败 code=${body.code} ${body.message} ${url}`)
        }
      } catch {
        // 非 JSON 响应体（如文件下载）忽略
      }
    }
  }

  return { pending, failures }
}

/** 等待网络静默并结算全部异步检查，返回失败清单（空数组 = 页面无失败请求） */
export async function settleFailures(page: Page, collector: FailureCollector): Promise<string[]> {
  await page.waitForLoadState('networkidle')
  await Promise.all(collector.pending)
  return collector.failures
}

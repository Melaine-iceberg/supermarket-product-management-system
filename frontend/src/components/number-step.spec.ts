import { describe, expect, it } from 'vitest'
import { resolveNumberStep } from './number-step'

describe('resolveNumberStep', () => {
  it('2 位小数金额使用 0.01 步长，避免 20.35 被吸附成 20.01', () => {
    expect(resolveNumberStep(undefined, 2)).toBe(0.01)
  })

  it('3 位小数重量使用 0.001 步长', () => {
    expect(resolveNumberStep(undefined, 3)).toBe(0.001)
  })

  it('整数或未指定精度时保持步长 1', () => {
    expect(resolveNumberStep(undefined, 0)).toBe(1)
    expect(resolveNumberStep(undefined, undefined)).toBe(1)
  })

  it('显式 step 优先，覆盖精度推导', () => {
    expect(resolveNumberStep(0.05, 2)).toBe(0.05)
    expect(resolveNumberStep(1, 0)).toBe(1)
  })
})

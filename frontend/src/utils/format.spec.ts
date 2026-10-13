import { describe, expect, it } from 'vitest'
import { moneyText } from './format'

/**
 * 金额格式化测试（IR-UI-05/GAP-P2-25）：千分位 + 固定小数位。
 */
describe('moneyText', () => {
  it('千分位 + 2 位小数', () => {
    expect(moneyText(1234567.8)).toBe('1,234,567.80')
    expect(moneyText(0)).toBe('0.00')
    expect(moneyText(12)).toBe('12.00')
    expect(moneyText(-1234.5)).toBe('-1,234.50')
  })

  it('支持 4 位小数（成本单价，DR-C-07）', () => {
    expect(moneyText(1234.5678, 4)).toBe('1,234.5678')
    expect(moneyText('0.5', 4)).toBe('0.5000')
  })

  it('数字字符串按金额格式化；非数字原样返回不抛错', () => {
    expect(moneyText('1234.5')).toBe('1,234.50')
    expect(moneyText('abc')).toBe('abc')
  })
})

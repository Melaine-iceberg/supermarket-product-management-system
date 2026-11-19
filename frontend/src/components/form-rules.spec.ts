import { describe, expect, it } from 'vitest'
import { sanitizeRule, sanitizeRules } from './form-rules'

describe('sanitizeRule', () => {
  it('剥离 Element Plus 的 trigger，保留 async-validator 需要的字段', () => {
    const rule = { required: true, message: '请输入零售单价', trigger: 'blur' }
    expect(sanitizeRule(rule)).toEqual({ required: true, message: '请输入零售单价' })
  })

  it('保留自定义 validator / pattern / type', () => {
    const validator = () => undefined
    expect(sanitizeRule({ validator, trigger: 'blur' })).toEqual({ validator })
    expect(sanitizeRule({ pattern: /^\d+$/, trigger: 'blur' })).toEqual({ pattern: /^\d+$/ })
    expect(sanitizeRule({ type: 'number', required: true, trigger: 'change' })).toEqual({
      type: 'number',
      required: true,
    })
  })

  it('不修改原始规则对象', () => {
    const rule = { required: true, trigger: 'change' }
    sanitizeRule(rule)
    expect(rule.trigger).toBe('change')
  })

  it('批量处理并兼容非对象规则', () => {
    expect(sanitizeRules([{ required: true, trigger: 'blur' }, 'raw'])).toEqual([
      { required: true },
      'raw',
    ])
  })
})

<script setup lang="ts">
import { nextTick, provide, reactive, ref } from 'vue'
import Schema from 'async-validator'
import {
  appFormContextKey,
  type AppFormContext,
  type AppFormFieldRegistration,
} from './form-context'

const props = withDefaults(
  defineProps<{
    model?: Record<string, unknown>
    rules?: Record<string, unknown[]>
    labelWidth?: string
    inline?: boolean
    disabled?: boolean
  }>(),
  {
    model: () => ({}),
    rules: () => ({}),
    labelWidth: '',
    inline: false,
    disabled: false,
  },
)

const errors = reactive<Record<string, string>>({})
const fields = new Map<string, AppFormFieldRegistration>()
const formEl = ref<HTMLFormElement>()

function clearErrors(propList?: string[]) {
  if (!propList) {
    for (const key of Object.keys(errors)) delete errors[key]
    return
  }
  for (const prop of propList) delete errors[prop]
}

function pickRules(propList: string[]): Record<string, unknown[]> {
  const picked: Record<string, unknown[]> = {}
  for (const prop of propList) {
    const rule = props.rules[prop]
    if (rule?.length) picked[prop] = rule
  }
  return picked
}

async function validateProps(propList: string[]): Promise<void> {
  const picked = pickRules(propList)
  if (Object.keys(picked).length === 0) {
    clearErrors(propList)
    return
  }

  try {
    const validator = new Schema(picked as never)
    await validator.validate(props.model as never)
    clearErrors(propList)
  } catch (error) {
    clearErrors(propList)
    const fieldsWithError = (error as { fields?: Record<string, { message?: string }[]> })
      .fields
    if (fieldsWithError) {
      for (const [prop, list] of Object.entries(fieldsWithError)) {
        errors[prop] = list?.[0]?.message || '校验失败'
      }
    }
    throw error
  }
}

async function validate(callback?: (valid: boolean, invalidFields?: unknown) => void) {
  try {
    await validateProps(Object.keys(props.rules))
    callback?.(true)
    return true
  } catch (error) {
    callback?.(false, error)
    // 可访问性：提交失败后把焦点移到第一个错误字段，屏幕阅读器会随焦点朗读错误
    await nextTick()
    formEl.value?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
    throw error
  }
}

async function validateField(prop: string) {
  await validateProps([prop])
}

function clearValidate(propList?: string | string[]) {
  if (!propList) clearErrors()
  else clearErrors(Array.isArray(propList) ? propList : [propList])
}

function clone(value: unknown): unknown {
  if (value === null || value === undefined) return value
  if (typeof structuredClone === 'function') {
    try {
      return structuredClone(value)
    } catch {
      // File/函数等不可克隆对象退回原值
      return value
    }
  }
  return value
}

function resetFields() {
  for (const [prop, field] of fields) {
    // 表单重置需要写回父组件传入的 model（Element Plus 的 resetFields 语义）
    // eslint-disable-next-line vue/no-mutating-props
    props.model[prop] = clone(field.initialValue)
  }
  clearErrors()
}

function addField(field: AppFormFieldRegistration) {
  fields.set(field.prop, field)
}

function removeField(prop: string) {
  fields.delete(prop)
  delete errors[prop]
}

const context: AppFormContext = {
  model: props.model,
  rules: props.rules,
  errors,
  labelWidth: props.labelWidth,
  inline: props.inline,
  disabled: props.disabled,
  addField,
  removeField,
  validateField,
}

provide(appFormContextKey, context)

defineExpose({
  validate,
  validateField,
  clearValidate,
  resetFields,
})
</script>

<template>
  <form
    ref="formEl"
    class="app-form"
    :class="{ 'app-form-inline': inline }"
    novalidate
    @submit.prevent
  >
    <slot />
  </form>
</template>

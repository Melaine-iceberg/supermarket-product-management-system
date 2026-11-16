import type { InjectionKey } from 'vue'

export interface AppFormFieldRegistration {
  prop: string
  initialValue: unknown
}

export interface AppFormContext {
  model: Record<string, unknown>
  rules: Record<string, unknown[]>
  errors: Record<string, string>
  labelWidth: string
  inline: boolean
  disabled: boolean
  addField: (field: AppFormFieldRegistration) => void
  removeField: (prop: string) => void
  validateField: (prop: string) => Promise<void>
}

export const appFormContextKey: InjectionKey<AppFormContext> = Symbol('AppForm')

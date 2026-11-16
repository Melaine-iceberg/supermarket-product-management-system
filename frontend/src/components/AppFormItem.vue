<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted } from 'vue'
import { appFormContextKey } from './form-context'

const props = withDefaults(
  defineProps<{
    label?: string
    prop?: string
    required?: boolean
    labelWidth?: string
    error?: string
    showMessage?: boolean
  }>(),
  {
    label: '',
    prop: '',
    required: false,
    labelWidth: '',
    error: '',
    showMessage: true,
  },
)

const form = inject(appFormContextKey, null)

const modelValue = computed(() => (props.prop && form ? form.model[props.prop] : undefined))

onMounted(() => {
  if (props.prop && form) {
    form.addField({ prop: props.prop, initialValue: modelValue.value })
  }
})

onBeforeUnmount(() => {
  if (props.prop && form) form.removeField(props.prop)
})

const fieldError = computed(() => {
  if (props.error) return props.error
  if (props.prop && form) return form.errors[props.prop] || undefined
  return undefined
})

const isRequired = computed(() => {
  if (props.required) return true
  if (!props.prop || !form) return false
  return !!form.rules[props.prop]?.some(
    (rule) => (rule as { required?: boolean }).required === true,
  )
})

const resolvedLabelWidth = computed(() => props.labelWidth || form?.labelWidth || '')
const horizontal = computed(() => !!resolvedLabelWidth.value || !!form?.inline)

const fieldUi = computed(() =>
  horizontal.value
    ? {
        root: 'flex! items-start! justify-start! gap-3',
        label: 'shrink-0 pt-0.5',
        container: 'mt-0! flex-1 min-w-0',
        error: 'mt-1 text-xs',
      }
    : {
        container: 'mt-1',
        error: 'mt-1 text-xs',
      },
)

const blurRules = computed(() => {
  if (!props.prop || !form) return false
  return !!form.rules[props.prop]?.some(
    (rule) => (rule as { trigger?: string }).trigger === 'blur',
  )
})

function onFocusout() {
  if (!blurRules.value || !props.prop || !form) return
  void form.validateField(props.prop).catch(() => undefined)
}

/**
 * 错误已展示时，用户输入/修改后立即重新校验：修好即清除错误，避免一直红着。
 * 未出现过错误的字段不提前校验，符合“交互后再提示”的表单规范。
 */
function onInput() {
  if (!props.prop || !form || !form.errors[props.prop]) return
  void form.validateField(props.prop).catch(() => undefined)
}
</script>

<template>
  <div class="app-form-item mb-4" @focusout="onFocusout" @input="onInput" @change="onInput">
    <div v-if="!label && !prop" class="app-form-plain">
      <slot />
    </div>
    <UFormField
      v-else
      :label="resolvedLabelWidth ? undefined : label"
      :error="showMessage ? fieldError || undefined : undefined"
      :required="isRequired"
      :orientation="horizontal ? 'horizontal' : 'vertical'"
      :ui="fieldUi"
    >
      <template v-if="resolvedLabelWidth" #label>
        <span class="app-form-label" :style="{ width: resolvedLabelWidth }">
          {{ label }}
        </span>
      </template>
      <slot />
    </UFormField>
  </div>
</template>

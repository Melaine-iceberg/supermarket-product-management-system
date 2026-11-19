<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, useId } from 'vue'
import { appFormContextKey } from './form-context'

const props = withDefaults(
  defineProps<{
    label?: string
    prop?: string
    required?: boolean
    labelWidth?: string
    error?: string
    showMessage?: boolean
    /**
     * 标签渲染方式：
     * - label（默认）：使用 UFormField 的 <label for>，适合单个原生/Nuxt UI 输入控件；
     * - span：用于单选组、图片上传等“非单一表单控件”，避免生成指向不存在 id 的 <label>。
     */
    labelAs?: 'label' | 'span'
  }>(),
  {
    label: '',
    prop: '',
    required: false,
    labelWidth: '',
    error: '',
    showMessage: true,
    labelAs: 'label',
  },
)

const customLabelId = useId()

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
    <div v-else-if="labelAs === 'span'" :class="horizontal ? 'flex items-start gap-3' : ''">
      <span
        v-if="label"
        :id="customLabelId"
        class="block shrink-0 pt-0.5 font-medium text-default"
        :class="horizontal ? '' : 'mb-1'"
        :style="{ width: horizontal ? resolvedLabelWidth || undefined : undefined }"
      >
        {{ label }}<span v-if="isRequired" class="ms-0.5 text-error">*</span>
      </span>
      <div :class="horizontal ? 'min-w-0 flex-1' : ''">
        <div role="group" :aria-labelledby="label ? customLabelId : undefined">
          <slot />
        </div>
        <div v-if="showMessage && fieldError" class="mt-1 text-xs text-error">
          {{ fieldError }}
        </div>
      </div>
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

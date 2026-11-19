<script setup lang="ts">
import { computed } from 'vue'
import { resolveNumberStep } from './number-step'

defineOptions({ inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    modelValue?: number | null
    min?: number
    max?: number
    step?: number
    precision?: number
    disabled?: boolean
    placeholder?: string
    controls?: boolean
    size?: 'large' | 'default' | 'small'
  }>(),
  {
    modelValue: null,
    min: undefined,
    max: undefined,
    // 未显式指定 step 时按 precision 推导，避免金额/重量等小数被吸附到整数步长
    step: undefined,
    precision: undefined,
    disabled: false,
    placeholder: '',
    controls: true,
    size: 'default',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
  change: [value: number | null]
}>()

const model = computed({
  get: () => props.modelValue,
  set: (value: number | null) => {
    emit('update:modelValue', value)
    emit('change', value)
  },
})

const mappedSize = computed(() => {
  if (props.size === 'large') return 'lg'
  if (props.size === 'small') return 'sm'
  return 'md'
})

const resolvedStep = computed(() => resolveNumberStep(props.step, props.precision))

const formatOptions = computed(() => {
  if (props.precision == null) return undefined
  return {
    minimumFractionDigits: props.precision,
    maximumFractionDigits: props.precision,
  }
})
</script>

<template>
  <UInputNumber
    v-model="model"
    v-bind="$attrs"
    :min="min"
    :max="max"
    :step="resolvedStep"
    :format-options="formatOptions"
    :disabled="disabled"
    :placeholder="placeholder"
    :size="mappedSize"
    :increment="controls ? undefined : false"
    :decrement="controls ? undefined : false"
    class="w-full"
  />
</template>

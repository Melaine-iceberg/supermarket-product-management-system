<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import { computed } from 'vue'
import { parseDate } from '@internationalized/date'

defineOptions({ inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    modelValue?: string | string[] | null
    type?: 'date' | 'daterange' | 'datetimerange' | 'month' | 'year'
    valueFormat?: string
    placeholder?: string
    startPlaceholder?: string
    endPlaceholder?: string
    disabled?: boolean
    clearable?: boolean
    size?: 'large' | 'default' | 'small'
  }>(),
  {
    modelValue: null,
    type: 'date',
    valueFormat: 'YYYY-MM-DD',
    placeholder: '',
    startPlaceholder: '开始',
    endPlaceholder: '结束',
    disabled: false,
    clearable: true,
    size: 'default',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string | string[] | null]
  change: [value: string | string[] | null]
}>()

const mappedSize = computed(() => {
  if (props.size === 'large') return 'lg'
  if (props.size === 'small') return 'sm'
  return 'md'
})

function toCalendarDate(value: unknown) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}/.test(value)) return undefined
  try {
    return parseDate(value.slice(0, 10))
  } catch {
    return undefined
  }
}

function emitValue(value: string | string[] | null) {
  emit('update:modelValue', value)
  emit('change', value)
}

const single = computed({
  get: () => toCalendarDate(props.modelValue),
  set: (value: any) => emitValue(value ? value.toString() : ''),
})

const range = computed({
  get: () => {
    const value = props.modelValue
    if (!Array.isArray(value) || value.length !== 2) return undefined
    const start = toCalendarDate(value[0])
    const end = toCalendarDate(value[1])
    return start && end ? { start, end } : undefined
  },
  set: (value: any) => {
    if (!value?.start || !value?.end) {
      emitValue([])
      return
    }
    emitValue([value.start.toString(), value.end.toString()])
  },
})

function toNative(value: unknown) {
  return typeof value === 'string' ? value.replace(' ', 'T').slice(0, 16) : ''
}

function fromNative(value: string) {
  if (!value) return ''
  const normalized = value.replace('T', ' ')
  return normalized.length === 16 ? `${normalized}:00` : normalized.slice(0, 19)
}

const dateTimeStart = computed({
  get: () => (Array.isArray(props.modelValue) ? toNative(props.modelValue[0]) : ''),
  set: (value: string) => {
    const end = Array.isArray(props.modelValue) ? props.modelValue[1] : ''
    emitValue([fromNative(value), end ?? ''])
  },
})

const dateTimeEnd = computed({
  get: () => (Array.isArray(props.modelValue) ? toNative(props.modelValue[1]) : ''),
  set: (value: string) => {
    const start = Array.isArray(props.modelValue) ? props.modelValue[0] : ''
    emitValue([start ?? '', fromNative(value)])
  },
})
</script>

<template>
  <div v-if="type === 'datetimerange'" class="app-date-picker flex items-center gap-2" v-bind="$attrs">
    <UInput
      :model-value="dateTimeStart"
      type="datetime-local"
      :disabled="disabled"
      :size="mappedSize"
      class="flex-1"
      @update:model-value="dateTimeStart = String($event)"
    />
    <span class="text-muted">至</span>
    <UInput
      :model-value="dateTimeEnd"
      type="datetime-local"
      :disabled="disabled"
      :size="mappedSize"
      class="flex-1"
      @update:model-value="dateTimeEnd = String($event)"
    />
  </div>
  <UInputDate
    v-else-if="type === 'daterange'"
    v-model="range"
    v-bind="$attrs"
    class="app-date-picker"
    range
    :disabled="disabled"
    :size="mappedSize"
  />
  <UInput
    v-else-if="type === 'month'"
    v-bind="$attrs"
    class="app-date-picker"
    :model-value="typeof modelValue === 'string' ? modelValue.slice(0, 7) : ''"
    type="month"
    :disabled="disabled"
    :size="mappedSize"
    @update:model-value="emitValue(String($event))"
  />
  <UInput
    v-else-if="type === 'year'"
    v-bind="$attrs"
    class="app-date-picker"
    :model-value="typeof modelValue === 'string' ? modelValue.slice(0, 4) : ''"
    type="number"
    min="1970"
    max="2999"
    :disabled="disabled"
    :size="mappedSize"
    @update:model-value="emitValue(String($event))"
  />
  <UInputDate
    v-else
    v-model="single"
    v-bind="$attrs"
    class="app-date-picker"
    :disabled="disabled"
    :size="mappedSize"
  />
</template>

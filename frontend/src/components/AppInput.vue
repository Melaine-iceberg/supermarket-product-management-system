<script setup lang="ts">
import { computed, ref } from 'vue'
import { iconName } from '@/utils/icons'

defineOptions({ inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    modelValue?: string | number | null
    type?: string
    placeholder?: string
    disabled?: boolean
    readonly?: boolean
    clearable?: boolean
    showPassword?: boolean
    prefixIcon?: string
    suffixIcon?: string
    size?: 'large' | 'default' | 'small'
    maxlength?: number | string
    rows?: number | string
    autosize?: boolean | { minRows?: number; maxRows?: number }
  }>(),
  {
    modelValue: '',
    type: 'text',
    placeholder: '',
    disabled: false,
    readonly: false,
    clearable: false,
    showPassword: false,
    prefixIcon: '',
    suffixIcon: '',
    size: 'default',
    maxlength: undefined,
    rows: 3,
    autosize: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string | number | null]
  change: [value: string | number | null]
  clear: []
  blur: [event: FocusEvent]
  focus: [event: FocusEvent]
}>()

const passwordVisible = ref(false)

const model = computed({
  get: () => (props.modelValue == null ? '' : String(props.modelValue)),
  set: (value: string) => {
    emit('update:modelValue', value)
    emit('change', value)
  },
})

const mappedSize = computed(() => {
  if (props.size === 'large') return 'lg'
  if (props.size === 'small') return 'sm'
  return 'md'
})

const actualType = computed(() => {
  if (props.showPassword && props.type === 'password') {
    return passwordVisible.value ? 'text' : 'password'
  }
  return props.type
})

const hasValue = computed(
  () => props.modelValue !== '' && props.modelValue !== null && props.modelValue !== undefined,
)

const leadingIcon = computed(() => iconName(props.prefixIcon))
const trailingIcon = computed(() => iconName(props.suffixIcon))

function clear() {
  model.value = ''
  emit('clear')
}

const rows = computed(() => {
  if (typeof props.autosize === 'object' && props.autosize.minRows) return props.autosize.minRows
  return Number(props.rows) || 3
})
</script>

<template>
  <UTextarea
    v-if="type === 'textarea'"
    v-model="model"
    v-bind="$attrs"
    :placeholder="placeholder"
    :disabled="disabled"
    :readonly="readonly"
    :rows="rows"
    :maxlength="maxlength"
    :size="mappedSize"
    class="w-full"
    @blur="emit('blur', $event)"
    @focus="emit('focus', $event)"
  />
  <UInput
    v-else
    v-model="model"
    v-bind="$attrs"
    :type="actualType"
    :placeholder="placeholder"
    :disabled="disabled"
    :readonly="readonly"
    :size="mappedSize"
    :maxlength="maxlength"
    :icon="leadingIcon"
    @blur="emit('blur', $event)"
    @focus="emit('focus', $event)"
  >
    <template v-if="showPassword || clearable || trailingIcon" #trailing>
      <UButton
        v-if="showPassword && type === 'password'"
        :icon="passwordVisible ? 'i-lucide-eye-off' : 'i-lucide-eye'"
        color="neutral"
        variant="link"
        size="xs"
        :aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
        tabindex="-1"
        @click="passwordVisible = !passwordVisible"
      />
      <UButton
        v-if="clearable && hasValue && !disabled && !readonly"
        icon="i-lucide-x"
        color="neutral"
        variant="link"
        size="xs"
        aria-label="清空"
        tabindex="-1"
        @click="clear"
      />
      <UIcon v-if="trailingIcon" :name="trailingIcon" class="size-4 text-dimmed" />
    </template>
  </UInput>
</template>

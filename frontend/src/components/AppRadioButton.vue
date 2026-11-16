<script setup lang="ts">
/** el-radio-button 的迁移别名，由 AppRadioGroup 读取。 */
withDefaults(
  defineProps<{
    label?: string
    value?: string | number | boolean
    disabled?: boolean
  }>(),
  {
    label: '',
    value: '',
    disabled: false,
  },
)
</script>

<template>
  <span class="hidden" aria-hidden="true" />
</template>

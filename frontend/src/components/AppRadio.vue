<script setup lang="ts">
/** 声明式单选项（由 AppRadioGroup 读取，不会单独渲染）。 */
withDefaults(
  defineProps<{
    label?: string
    value?: string | number | boolean
    disabled?: boolean
    button?: boolean
  }>(),
  {
    label: '',
    value: '',
    disabled: false,
    button: false,
  },
)
</script>

<template>
  <span class="hidden" aria-hidden="true" />
</template>

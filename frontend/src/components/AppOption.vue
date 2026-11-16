<script setup lang="ts">
/** 声明式下拉选项（由 AppSelect 读取，不会单独渲染）。 */
withDefaults(
  defineProps<{
    label?: string
    value?: string | number | boolean | null
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

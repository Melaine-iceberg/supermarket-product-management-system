<script setup lang="ts">
/** 声明式描述项（由 AppDescriptions 读取，不会单独渲染）。 */
withDefaults(
  defineProps<{
    label?: string
    span?: number
    width?: string | number
    labelWidth?: string | number
  }>(),
  {
    label: '',
    span: 1,
    width: '',
    labelWidth: '',
  },
)
</script>

<template>
  <span class="hidden" aria-hidden="true" />
</template>

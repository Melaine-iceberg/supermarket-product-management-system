<script setup lang="ts">
/** 声明式标签页（由 AppTabs 读取，不会单独渲染）。 */
withDefaults(
  defineProps<{
    label?: string
    name?: string | number
    disabled?: boolean
    closable?: boolean
    lazy?: boolean
  }>(),
  {
    label: '',
    name: '',
    disabled: false,
    closable: false,
    lazy: false,
  },
)
</script>

<template>
  <span class="hidden" aria-hidden="true" />
</template>

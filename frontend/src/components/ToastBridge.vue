<script setup lang="ts">
import { useToast } from '@nuxt/ui/composables'
import { bindToast } from '@/utils/notify'

// 将 Nuxt UI 的 toast 实例交给非组件代码（拦截器、异步任务）使用
const toast = useToast()
bindToast(toast)
</script>

<template>
  <span class="hidden" aria-hidden="true" />
</template>

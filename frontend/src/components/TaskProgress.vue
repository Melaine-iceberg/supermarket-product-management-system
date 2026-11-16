<script setup lang="ts">
import { loadingState } from '@/utils/loading'
</script>

<template>
  <Teleport to="body">
    <div
      v-if="loadingState.count > 0"
      class="fixed inset-0 z-[9999] grid place-items-center bg-elevated/60 p-4 backdrop-blur-sm"
      role="status"
      aria-live="polite"
    >
      <div
        class="flex max-w-md items-center gap-3 rounded-xl bg-default px-5 py-4 shadow-xl ring ring-default"
      >
        <UIcon name="i-lucide-loader-circle" class="size-5 shrink-0 animate-spin text-primary" />
        <span class="text-sm text-default">{{ loadingState.text || '处理中…' }}</span>
      </div>
    </div>
  </Teleport>
</template>

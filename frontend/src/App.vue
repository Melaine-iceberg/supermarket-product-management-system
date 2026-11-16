<script setup lang="ts">
import { zh_cn } from '@nuxt/ui/locale'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import PwaPrompt from '@/components/PwaPrompt.vue'
import TaskProgress from '@/components/TaskProgress.vue'
import ToastBridge from '@/components/ToastBridge.vue'
</script>

<template>
  <UApp :locale="zh_cn">
    <router-view />
    <PwaPrompt />
    <ToastBridge />
    <ConfirmDialog />
    <TaskProgress />
  </UApp>
</template>

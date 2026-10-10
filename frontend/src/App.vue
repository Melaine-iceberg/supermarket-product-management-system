<script setup lang="ts">
// 全局中文语言包：避免 Element Plus 组件出现 No Data / Total / Start Date 等英文文案
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import PwaPrompt from '@/components/PwaPrompt.vue'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
    <PwaPrompt />
  </el-config-provider>
</template>

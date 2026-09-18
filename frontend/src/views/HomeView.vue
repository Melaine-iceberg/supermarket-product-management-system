<script setup lang="ts">
import { ref } from 'vue'
import { ping, type PingResult } from '@/api/health'

const loading = ref(false)
const result = ref<PingResult | null>(null)

async function handlePing() {
  loading.value = true
  try {
    result.value = await ping()
    ElMessage.success('后端连接正常')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="home">
    <el-card class="home-card">
      <template #header>
        <span class="title">超市商品管理系统</span>
        <el-tag type="info" effect="plain">工程骨架已就绪</el-tag>
      </template>
      <p class="desc">前后端分离架构：Vue 3 + Element Plus 前端，Spring Boot 后端（/api/v1）。</p>
      <el-button type="primary" :loading="loading" @click="handlePing">
        测试后端连通
      </el-button>
      <el-descriptions v-if="result" :column="1" border class="result">
        <el-descriptions-item label="后端应用">{{ result.app }}</el-descriptions-item>
        <el-descriptions-item label="服务器时间">{{ result.serverTime }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </main>
</template>

<style scoped lang="scss">
.home {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}

.home-card {
  width: 520px;

  .title {
    font-size: 18px;
    font-weight: 600;
    margin-right: 12px;
  }

  .desc {
    color: #606266;
    margin: 0 0 16px;
  }

  .result {
    margin-top: 16px;
  }
}
</style>

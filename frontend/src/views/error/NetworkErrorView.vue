<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * 网络异常统一错误页（IR-UI-07/GAP-P2-25）：
 * 请求层发现无法连接服务器时跳转至此，提供"重试"（回到原页面并重新加载）与返回首页。
 * 路由参数 redirect 记录中断前页面路径；重试按钮本身是用户手势，直接重放导航。
 */
const route = useRoute()
const router = useRouter()

const redirect = computed(() =>
  typeof route.query.redirect === 'string' && route.query.redirect ? route.query.redirect : '/home',
)

function retry() {
  // 回到原页面并整页重载，确保被中断的请求重新发起
  window.location.assign(redirect.value)
}
</script>

<template>
  <div class="network-error">
    <el-result
      icon="warning"
      title="网络连接异常"
      sub-title="无法连接到服务器，请检查本机网络或服务端是否正常运行"
    >
      <template #extra>
        <el-button type="primary" @click="retry">重试</el-button>
        <el-button @click="router.push('/home')">返回首页</el-button>
      </template>
    </el-result>
    <div class="hint">
      若多次重试仍失败，请联系管理员检查后端服务与数据库状态（部署手册 §6 故障排查）。
    </div>
  </div>
</template>

<style scoped lang="scss">
.network-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: #f5f7fa;

  .hint {
    margin-top: 8px;
    font-size: 12px;
    color: #909399;
  }
}
</style>

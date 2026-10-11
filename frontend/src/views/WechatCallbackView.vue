<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 微信授权回调页（V1.2）。
 *
 * <p>微信跳回时携带 code 与 state，本页把它们交给后端换取登录态。
 * state 的有效性由后端校验（一次性、5 分钟过期），前端不做判断。</p>
 *
 * <p>回调地址需在微信开放平台配置为本站的 /auth/wechat/callback（hash 模式见路由配置）。</p>
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const status = ref<'processing' | 'failed'>('processing')
const message = ref('正在完成微信登录，请稍候…')

onMounted(async () => {
  const code = route.query.code as string | undefined
  const state = route.query.state as string | undefined

  if (!code || !state) {
    status.value = 'failed'
    message.value = '微信授权信息不完整，请返回登录页重试'
    return
  }

  try {
    await auth.loginByWechat(code, state)
    router.replace('/home')
  } catch (error) {
    status.value = 'failed'
    // 常见原因：未绑定账号、state 过期、开放平台配置不匹配
    message.value = error instanceof Error ? error.message : '微信登录失败，请返回登录页重试'
  }
})
</script>

<template>
  <div class="wechat-callback">
    <el-card class="callback-card">
      <el-icon v-if="status === 'processing'" :size="40" color="#409eff" class="is-loading">
        <Loading />
      </el-icon>
      <el-icon v-else :size="40" color="#f56c6c"><CircleClose /></el-icon>
      <p class="message">{{ message }}</p>
      <el-button v-if="status === 'failed'" type="primary" @click="router.replace('/login')">
        返回登录页
      </el-button>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.wechat-callback {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;

  .callback-card {
    width: 360px;
    text-align: center;
    border-radius: 10px;

    .message {
      margin: 12px 0 16px;
      color: #606266;
      font-size: 14px;
    }
  }
}
</style>

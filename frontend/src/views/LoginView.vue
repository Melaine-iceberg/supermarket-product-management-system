<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { fetchStoreName } from '@/api/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const storeName = ref('超市商品管理系统')
const loading = ref(false)
const formRef = ref()

const form = reactive({
  username: '',
  password: '',
  rememberMe: false,
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

onMounted(async () => {
  try {
    storeName.value = await fetchStoreName()
  } catch {
    /* 后端未启动时使用默认标题 */
  }
})

async function handleLogin() {
  await formRef.value.validate()
  loading.value = true
  try {
    await auth.login({ ...form })
    const redirect = (route.query.redirect as string) || '/home'
    router.push(redirect)
  } catch {
    // 错误提示由拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="login-head">
        <el-icon :size="40" color="#409eff"><ShoppingCart /></el-icon>
        <h2 class="title">{{ storeName }}</h2>
        <p class="sub">超市商品管理系统</p>
      </div>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        size="large"
        @keyup.enter="handleLogin"
      >
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="'User'" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            show-password
            :prefix-icon="'Lock'"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.rememberMe">记住我（72 小时内免登录）</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="login-btn" :loading="loading" @click="handleLogin">
            登 录
          </el-button>
        </el-form-item>
      </el-form>
      <p class="tip">演示账号：admin / Admin@2026</p>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #e8f1ff 0%, #f5f7fa 60%, #eef4ff 100%);
}

.login-card {
  width: 400px;
  border-radius: 10px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.08);

  .login-head {
    text-align: center;
    margin-bottom: 20px;

    .title {
      margin: 10px 0 4px;
      font-size: 20px;
      color: #303133;
    }

    .sub {
      margin: 0;
      font-size: 13px;
      color: #909399;
    }
  }

  .login-btn {
    width: 100%;
  }

  .tip {
    text-align: center;
    color: #c0c4cc;
    font-size: 12px;
    margin: 4px 0 0;
  }
}
</style>

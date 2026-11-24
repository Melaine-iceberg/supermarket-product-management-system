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
const formRef = ref<{ validate: () => Promise<boolean> }>()

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
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
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
    <UCard class="login-card w-full max-w-[400px]" :ui="{ body: 'p-6 sm:p-7' }">
      <div class="login-head">
        <UIcon name="i-lucide-shopping-cart" class="mx-auto size-10 text-primary" />
        <h2 class="title">{{ storeName }}</h2>
        <p class="sub">超市商品管理系统</p>
      </div>

      <AppForm ref="formRef" :model="form" :rules="rules" aria-label="登录表单">
        <AppFormItem prop="username">
          <AppInput
            v-model="form.username"
            name="username"
            autocomplete="username"
            autocapitalize="off"
            spellcheck="false"
            enterkeyhint="next"
            required
            size="large"
            aria-label="用户名"
            placeholder="用户名"
            prefix-icon="User"
          />
        </AppFormItem>

        <AppFormItem prop="password">
          <AppInput
            v-model="form.password"
            name="password"
            type="password"
            autocomplete="current-password"
            enterkeyhint="go"
            required
            size="large"
            aria-label="密码"
            placeholder="密码"
            prefix-icon="Lock"
            show-password
          />
        </AppFormItem>

        <AppFormItem>
          <UCheckbox v-model="form.rememberMe" name="rememberMe" label="记住我（72 小时内免登录）" />
        </AppFormItem>

        <AppFormItem>
          <UButton
            type="submit"
            size="lg"
            block
            :loading="loading"
            label="登 录"
            @click="handleLogin"
          />
        </AppFormItem>
      </AppForm>
    </UCard>
  </div>
</template>

<style scoped lang="scss">
.login-page {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(16px, env(safe-area-inset-top)) 16px max(16px, env(safe-area-inset-bottom));
  background: linear-gradient(
    135deg,
    var(--smms-login-start) 0%,
    var(--ui-bg-muted) 60%,
    var(--smms-login-end) 100%
  );
}

.login-card {
  border-radius: 10px;
  box-shadow: var(--el-box-shadow-light);

  .login-head {
    text-align: center;
    margin-bottom: 20px;

    .title {
      margin: 10px 0 4px;
      font-size: 20px;
      color: var(--ui-text);
    }

    .sub {
      margin: 0;
      font-size: 13px;
      color: var(--ui-text-muted);
    }
  }
}
</style>

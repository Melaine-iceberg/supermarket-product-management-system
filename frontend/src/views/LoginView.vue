<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { fetchLoginMethods, fetchStoreName, fetchWechatAuthUrl } from '@/api/auth'
import type { CodeChannel, LoginMethods } from '@/api/auth'
import AuthCodeInput from '@/components/AuthCodeInput.vue'

/**
 * 登录页（V1.2）：手机号/邮箱验证码、微信扫码、账号密码三种方式并存。
 * 具体开放哪些由后端 /auth/login-methods 下发，前端不写死，便于按环境关闭渠道。
 */
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const storeName = ref('超市商品管理系统')
const loading = ref(false)

/** 后端不可用时的兜底：仅保留用户名密码，保证老账号仍能登录 */
const fallbackMethods: LoginMethods = {
  password: true,
  phone: false,
  email: false,
  wechat: false,
  register: false,
  wechatAppId: null,
  wechatScope: 'snsapi_login',
  codeChannels: [],
}
const methods = ref<LoginMethods>({ ...fallbackMethods })

const activeTab = ref<'code' | 'password' | 'wechat'>('password')
const codeFormRef = ref()
const pwdFormRef = ref()

/** 验证码登录当前渠道：手机号与邮箱二选一 */
const codeChannel = ref<CodeChannel>('sms')

const codeForm = reactive({
  phone: '',
  email: '',
  code: '',
  rememberMe: false,
})

const pwdForm = reactive({
  username: '',
  password: '',
  rememberMe: false,
})

/** 验证码登录的收件目标，供 AuthCodeInput 判断能否发送 */
const codeTarget = computed(() => (codeChannel.value === 'sms' ? codeForm.phone : codeForm.email))

const codeRules = computed(() => ({
  phone:
    codeChannel.value === 'sms'
      ? [
          { required: true, message: '请输入手机号', trigger: 'blur' },
          { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
        ]
      : [],
  email:
    codeChannel.value === 'email'
      ? [
          { required: true, message: '请输入邮箱', trigger: 'blur' },
          { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
        ]
      : [],
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
}))

const pwdRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const showCodeLogin = computed(() => methods.value.phone || methods.value.email)
const showWechatLogin = computed(() => methods.value.wechat)

function gotoAfterLogin() {
  const redirect = (route.query.redirect as string) || '/home'
  router.push(redirect)
}

async function handleCodeLogin() {
  await codeFormRef.value.validate()
  loading.value = true
  try {
    await auth.loginByCode({
      loginType: codeChannel.value,
      ...(codeChannel.value === 'sms' ? { phone: codeForm.phone } : { email: codeForm.email }),
      code: codeForm.code,
      rememberMe: codeForm.rememberMe,
    })
    gotoAfterLogin()
  } catch {
    // 错误提示由拦截器统一处理
  } finally {
    loading.value = false
  }
}

async function handlePasswordLogin() {
  await pwdFormRef.value.validate()
  loading.value = true
  try {
    await auth.login({ ...pwdForm })
    gotoAfterLogin()
  } catch {
    // 错误提示由拦截器统一处理
  } finally {
    loading.value = false
  }
}

async function handleWechatLogin() {
  loading.value = true
  try {
    const { authUrl } = await fetchWechatAuthUrl()
    // state 由后端下发并在回调时校验，前端只负责透传
    window.location.href = authUrl
  } catch {
    ElMessage.error('微信登录暂时不可用，请改用其他方式登录')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    storeName.value = await fetchStoreName()
  } catch {
    /* 后端未启动时使用默认标题 */
  }
  try {
    methods.value = await fetchLoginMethods()
    // 有验证码登录就默认展示，它比记密码更常用
    activeTab.value = showCodeLogin.value ? 'code' : 'password'
    if (!methods.value.phone && methods.value.email) {
      codeChannel.value = 'email'
    }
  } catch {
    methods.value = { ...fallbackMethods }
  }
})
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="login-head">
        <el-icon :size="40" color="#409eff"><ShoppingCart /></el-icon>
        <h2 class="title">{{ storeName }}</h2>
        <p class="sub">超市商品管理系统</p>
      </div>

      <el-tabs v-model="activeTab" class="login-tabs">
        <!-- 手机号 / 邮箱验证码登录 -->
        <el-tab-pane v-if="showCodeLogin" name="code">
          <template #label>
            <span class="tab-label"><el-icon><Iphone /></el-icon>验证码登录</span>
          </template>
          <el-form
            ref="codeFormRef"
            :model="codeForm"
            :rules="codeRules"
            size="large"
            @keyup.enter="handleCodeLogin"
          >
            <el-form-item v-if="methods.phone && methods.email" class="channel-switch">
              <el-radio-group v-model="codeChannel" size="small">
                <el-radio-button value="sms">手机号</el-radio-button>
                <el-radio-button value="email">邮箱</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="codeChannel === 'sms'" prop="phone">
              <el-input
                v-model="codeForm.phone"
                placeholder="手机号"
                maxlength="11"
                :prefix-icon="'Iphone'"
              />
            </el-form-item>
            <el-form-item v-else prop="email">
              <el-input
                v-model="codeForm.email"
                placeholder="邮箱"
                :prefix-icon="'Message'"
              />
            </el-form-item>
            <el-form-item prop="code">
              <AuthCodeInput
                v-model="codeForm.code"
                :channel="codeChannel"
                scene="login"
                :target="codeTarget"
              />
            </el-form-item>
            <el-form-item>
              <el-checkbox v-model="codeForm.rememberMe">记住我（7 天内免登录）</el-checkbox>
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                class="login-btn"
                :loading="loading"
                @click="handleCodeLogin"
              >
                登 录
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 微信扫码登录 -->
        <el-tab-pane v-if="showWechatLogin" name="wechat">
          <template #label>
            <span class="tab-label"><el-icon><ChatDotRound /></el-icon>微信登录</span>
          </template>
          <div class="wechat-pane">
            <el-icon :size="56" color="#07c160"><ChatDotRound /></el-icon>
            <p class="wechat-tip">
              使用微信扫一扫，在打开的授权页中确认登录。<br />
              微信账号需先在本系统绑定后才能登录。
            </p>
            <el-button type="success" :loading="loading" @click="handleWechatLogin">
              前往微信授权
            </el-button>
          </div>
        </el-tab-pane>

        <!-- 账号密码登录（保留：兼容未绑定手机号/邮箱的旧账号） -->
        <el-tab-pane v-if="methods.password" name="password">
          <template #label>
            <span class="tab-label"><el-icon><Lock /></el-icon>账号密码</span>
          </template>
          <el-form
            ref="pwdFormRef"
            :model="pwdForm"
            :rules="pwdRules"
            size="large"
            @keyup.enter="handlePasswordLogin"
          >
            <el-form-item prop="username">
              <el-input v-model="pwdForm.username" placeholder="用户名" :prefix-icon="'User'" />
            </el-form-item>
            <el-form-item prop="password">
              <el-input
                v-model="pwdForm.password"
                type="password"
                placeholder="密码"
                show-password
                :prefix-icon="'Lock'"
              />
            </el-form-item>
            <el-form-item>
              <el-checkbox v-model="pwdForm.rememberMe">记住我（7 天内免登录）</el-checkbox>
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                class="login-btn"
                :loading="loading"
                @click="handlePasswordLogin"
              >
                登 录
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <div v-if="methods.register" class="register-entry">
        还没有账号？<el-link type="primary" :underline="false" @click="router.push('/register')">
          立即注册
        </el-link>
      </div>
      <p v-if="methods.password" class="tip">演示账号：admin / Admin@2026</p>
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
  width: 420px;
  border-radius: 10px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.08);

  .login-head {
    text-align: center;
    margin-bottom: 8px;

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

  .login-tabs {
    :deep(.el-tabs__item) {
      font-size: 13px;
    }
  }

  .tab-label {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }

  .channel-switch {
    margin-bottom: 12px;
  }

  .login-btn {
    width: 100%;
  }

  .wechat-pane {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    padding: 12px 0 4px;
    gap: 12px;

    .wechat-tip {
      margin: 0;
      font-size: 13px;
      color: #909399;
      line-height: 1.7;
    }
  }

  .register-entry {
    text-align: center;
    font-size: 13px;
    color: #606266;
    margin-top: 4px;
  }

  .tip {
    text-align: center;
    color: #c0c4cc;
    font-size: 12px;
    margin: 8px 0 0;
  }
}
</style>

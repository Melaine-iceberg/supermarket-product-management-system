<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { fetchLoginMethods, fetchStoreName } from '@/api/auth'
import type { CodeChannel, LoginMethods } from '@/api/auth'
import AuthCodeInput from '@/components/AuthCodeInput.vue'

/**
 * 员工自助注册（V1.2）：手机号/邮箱验证码验证身份，注册成功即为启用状态并自动登录。
 * 新账号只带默认角色（后端 smms.login.default-role-id 指定），管理权限仍需管理员另行分配。
 */
const router = useRouter()
const auth = useAuthStore()

const storeName = ref('超市商品管理系统')
const loading = ref(false)
const formRef = ref()

const methods = ref<LoginMethods | null>(null)
const codeChannel = ref<CodeChannel>('sms')

const form = reactive({
  username: '',
  realName: '',
  password: '',
  confirmPassword: '',
  phone: '',
  email: '',
  code: '',
})

/** 手机号与邮箱至少填一项，由后端最终判定；这里只保证界面上二选一可用 */
const target = computed(() => (codeChannel.value === 'sms' ? form.phone : form.email))

const rules = computed(() => ({
  username: [
    { required: true, message: '请输入登录用户名', trigger: 'blur' },
    { min: 3, max: 30, message: '用户名长度 3~30 位', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]+$/, message: '用户名只能包含字母、数字和下划线', trigger: 'blur' },
  ],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, message: '密码至少 8 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
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

async function handleRegister() {
  await formRef.value.validate()
  loading.value = true
  try {
    await auth.register({
      username: form.username,
      password: form.password,
      realName: form.realName,
      // 两个渠道都填了才能都提交；未选中的渠道如果是空串则不提交，避免后端判重误伤
      ...(form.phone ? { phone: form.phone } : {}),
      ...(form.email ? { email: form.email } : {}),
      code: form.code,
    })
    ElMessage.success('注册成功，已自动登录')
    router.push('/home')
  } catch {
    // 错误提示由拦截器统一处理
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
    if (!methods.value.register) {
      ElMessage.warning('当前未开放自助注册，请联系管理员开通账号')
      router.replace('/login')
      return
    }
    if (!methods.value.phone && methods.value.email) {
      codeChannel.value = 'email'
    }
  } catch {
    ElMessage.error('无法获取注册配置，请稍后重试')
  }
})
</script>

<template>
  <div class="register-page">
    <el-card class="register-card">
      <div class="register-head">
        <el-icon :size="36" color="#409eff"><ShoppingCart /></el-icon>
        <h2 class="title">{{ storeName }}</h2>
        <p class="sub">员工账号注册</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="登录用户名" prop="username">
          <el-input v-model="form.username" placeholder="用于账号密码登录，字母/数字/下划线" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="真实姓名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            placeholder="再次输入密码"
          />
        </el-form-item>

        <el-form-item v-if="methods?.phone && methods?.email" label="验证方式">
          <el-radio-group v-model="codeChannel" size="small">
            <el-radio-button value="sms">手机号</el-radio-button>
            <el-radio-button value="email">邮箱</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="codeChannel === 'sms'" label="手机号" prop="phone">
          <el-input v-model="form.phone" maxlength="11" placeholder="用于接收短信验证码" />
        </el-form-item>
        <el-form-item v-else label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="用于接收邮件验证码" />
        </el-form-item>

        <el-form-item label="验证码" prop="code">
          <AuthCodeInput
            v-model="form.code"
            :channel="codeChannel"
            scene="register"
            :target="target"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" class="register-btn" :loading="loading" @click="handleRegister">
            注册并登录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="back-entry">
        已有账号？<el-link type="primary" :underline="false" @click="router.push('/login')">
          返回登录
        </el-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 0;
  background: linear-gradient(135deg, #e8f1ff 0%, #f5f7fa 60%, #eef4ff 100%);
}

.register-card {
  width: 460px;
  border-radius: 10px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.08);

  .register-head {
    text-align: center;
    margin-bottom: 16px;

    .title {
      margin: 8px 0 4px;
      font-size: 20px;
      color: #303133;
    }

    .sub {
      margin: 0;
      font-size: 13px;
      color: #909399;
    }
  }

  .register-btn {
    width: 100%;
  }

  .back-entry {
    text-align: center;
    font-size: 13px;
    color: #606266;
  }
}
</style>

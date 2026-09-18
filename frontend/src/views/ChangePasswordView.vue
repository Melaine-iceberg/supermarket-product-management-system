<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { changePassword } from '@/api/auth'

const router = useRouter()
const auth = useAuthStore()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      validator: (_: unknown, value: string, callback: (error?: Error) => void) => {
        // BR-SYS-04：8~32 位，同时包含字母与数字
        if (value && (value.length < 8 || value.length > 32)) {
          callback(new Error('密码长度须为 8~32 位'))
        } else if (value && !(/[a-zA-Z]/.test(value) && /[0-9]/.test(value))) {
          callback(new Error('密码须同时包含字母与数字'))
        } else if (value === form.oldPassword) {
          callback(new Error('新密码不能与原密码相同'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_: unknown, value: string, callback: (error?: Error) => void) => {
        if (value !== form.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

async function handleSubmit() {
  await formRef.value.validate()
  loading.value = true
  try {
    await changePassword(form.oldPassword, form.newPassword)
    ElMessage.success('密码修改成功，请重新登录')
    auth.clear()
    router.push({ name: 'login' })
  } catch {
    // 错误提示由拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="change-password">
    <el-card class="pwd-card">
      <template #header>
        <div class="card-head">
          <span>修改密码</span>
          <el-tag v-if="auth.mustChangePwd" type="warning" size="small">首次登录须修改密码</el-tag>
        </div>
      </template>
      <el-alert
        v-if="auth.mustChangePwd"
        type="warning"
        :closable="false"
        show-icon
        title="管理员已重置您的密码，为保障账号安全，请先修改密码后再使用系统"
        class="alert"
      />
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
        @keyup.enter="handleSubmit"
      >
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleSubmit">提交修改</el-button>
        </el-form-item>
      </el-form>
      <p class="policy">密码要求：长度 8~32 位，须同时包含字母与数字</p>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.change-password {
  display: flex;
  justify-content: center;
  padding-top: 8vh;

  .pwd-card {
    width: 460px;

    .card-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .alert {
      margin-bottom: 16px;
    }

    .policy {
      color: #909399;
      font-size: 12px;
      text-align: center;
    }
  }
}
</style>

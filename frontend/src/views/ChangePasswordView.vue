<script setup lang="ts">
import { notify } from '@/utils/notify'
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { changePassword } from '@/api/auth'

const router = useRouter()
const auth = useAuthStore()
const formRef = ref<{ validate: () => Promise<boolean> }>()
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
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await changePassword(form.oldPassword, form.newPassword)
    notify.success('密码修改成功，请重新登录')
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
    <UCard class="pwd-card w-full max-w-[460px]">
      <template #header>
        <div class="flex items-center justify-between gap-2">
          <span class="font-medium text-default">修改密码</span>
          <UBadge v-if="auth.mustChangePwd" color="warning" variant="soft" size="sm">
            首次登录须修改密码
          </UBadge>
        </div>
      </template>

      <UAlert
        v-if="auth.mustChangePwd"
        color="warning"
        variant="soft"
        icon="i-lucide-triangle-alert"
        title="管理员已重置您的密码，为保障账号安全，请先修改密码后再使用系统"
        class="mb-4"
      />

      <AppForm
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
        @keyup.enter="handleSubmit"
      >
        <AppFormItem label="原密码" prop="oldPassword">
          <AppInput v-model="form.oldPassword" type="password" show-password />
        </AppFormItem>
        <AppFormItem label="新密码" prop="newPassword">
          <AppInput v-model="form.newPassword" type="password" show-password />
        </AppFormItem>
        <AppFormItem label="确认密码" prop="confirmPassword">
          <AppInput v-model="form.confirmPassword" type="password" show-password />
        </AppFormItem>
        <AppFormItem>
          <UButton :loading="loading" label="提交修改" @click="handleSubmit" />
        </AppFormItem>
      </AppForm>

      <p class="policy">密码要求：长度 8~32 位，须同时包含字母与数字</p>
    </UCard>
  </div>
</template>

<style scoped lang="scss">
.change-password {
  display: flex;
  justify-content: center;
  padding-top: 8vh;

  .policy {
    color: var(--ui-text-muted);
    font-size: 12px;
    text-align: center;
  }
}
</style>

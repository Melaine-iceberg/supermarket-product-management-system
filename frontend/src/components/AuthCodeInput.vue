<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { sendAuthCode, type CodeChannel, type CodeScene } from '@/api/auth'

/**
 * 验证码输入框（V1.2）：内置 60s 倒计时与发送前校验，避免用户重复点击。
 * 倒计时秒数以服务端返回的 resendAfterSeconds 为准，防止前后端配置不一致。
 */
const props = defineProps<{
  modelValue: string
  channel: CodeChannel
  scene: CodeScene
  /** 手机号或邮箱，未填写时禁用发送按钮 */
  target: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const sending = ref(false)
const countdown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

const canSend = computed(() => countdown.value === 0 && !sending.value && !!props.target)

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function startCountdown(seconds: number) {
  countdown.value = seconds
  stopTimer()
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      stopTimer()
      countdown.value = 0
    }
  }, 1000)
}

async function handleSend() {
  if (!canSend.value) return
  sending.value = true
  try {
    const isPhone = props.channel === 'sms'
    const result = await sendAuthCode({
      channel: props.channel,
      scene: props.scene,
      ...(isPhone ? { phone: props.target } : { email: props.target }),
    })
    startCountdown(result.resendAfterSeconds || 60)
    if (result.devCode) {
      // 开发模式：验证码只写后端日志，这里直接回填省去翻日志
      emit('update:modelValue', result.devCode)
      ElMessage.warning(`开发模式验证码：${result.devCode}（已自动填入）`)
    } else {
      ElMessage.success(isPhone ? '验证码已发送，请查看短信' : '验证码已发送，请查收邮件')
    }
  } catch {
    // 错误提示由请求拦截器统一处理
  } finally {
    sending.value = false
  }
}

onUnmounted(stopTimer)
</script>

<template>
  <div class="code-input">
    <el-input
      :model-value="modelValue"
      placeholder="验证码"
      maxlength="6"
      :prefix-icon="'Key'"
      @update:model-value="emit('update:modelValue', $event)"
    />
    <el-button class="send-btn" :disabled="!canSend" :loading="sending" @click="handleSend">
      {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
    </el-button>
  </div>
</template>

<style scoped lang="scss">
.code-input {
  display: flex;
  gap: 8px;
  width: 100%;

  .send-btn {
    flex: 0 0 auto;
    min-width: 104px;
  }
}
</style>

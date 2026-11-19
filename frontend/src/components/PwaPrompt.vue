<script setup lang="ts">
/**
 * PWA 交互入口：
 * 1) 监听 beforeinstallprompt，提供「安装到桌面」入口；
 * 2) 通过 useRegisterSW 检测新版本，提示用户刷新以更新应用外壳。
 * 离线能力仅覆盖应用外壳（静态资源），接口请求始终联网，故此处不缓存业务数据。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useRegisterSW } from 'virtual:pwa-register/vue'

/** Chromium 特有的安装提示事件（lib.dom 尚未内置该类型） */
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

const INSTALL_DISMISS_KEY = 'smms:pwa-install-dismissed-at'
const INSTALL_DISMISS_MS = 7 * 24 * 60 * 60 * 1000
const UPDATE_CHECK_INTERVAL_MS = 60 * 60 * 1000

const canInstall = ref(false)
const route = useRoute()

/** 安装提示只放在首页/登录页，避免在列表页遮挡右下角分页与底部操作按钮 */
const showInstallPrompt = computed(
  () => canInstall.value && ['home', 'login'].includes(String(route.name ?? '')),
)

let installEvent: BeforeInstallPromptEvent | null = null
let updateTimer: number | undefined
let offlineTimer: number | undefined

const { needRefresh, offlineReady, updateServiceWorker } = useRegisterSW({
  onRegisteredSW(_swUrl, registration) {
    if (!registration) return
    // 长期打开的后台页面也能拿到新版本
    updateTimer = window.setInterval(() => {
      void registration.update()
    }, UPDATE_CHECK_INTERVAL_MS)
  },
  onOfflineReady() {
    offlineTimer = window.setTimeout(() => {
      offlineReady.value = false
    }, 3000)
  },
})

/** 已安装（独立窗口）时不展示安装入口 */
function isStandalone(): boolean {
  if (window.matchMedia('(display-mode: standalone)').matches) return true
  return (window.navigator as { standalone?: boolean }).standalone === true
}

function isInstallDismissed(): boolean {
  const raw = window.localStorage.getItem(INSTALL_DISMISS_KEY)
  if (!raw) return false
  const dismissedAt = Number(raw)
  return Number.isFinite(dismissedAt) && Date.now() - dismissedAt < INSTALL_DISMISS_MS
}

function onBeforeInstallPrompt(event: Event) {
  event.preventDefault()
  installEvent = event as BeforeInstallPromptEvent
  canInstall.value = !isStandalone() && !isInstallDismissed()
}

function onAppInstalled() {
  installEvent = null
  canInstall.value = false
}

async function install() {
  const event = installEvent
  installEvent = null
  canInstall.value = false
  if (!event) return
  await event.prompt()
  const choice = await event.userChoice
  if (choice.outcome === 'dismissed') {
    window.localStorage.setItem(INSTALL_DISMISS_KEY, String(Date.now()))
  }
}

function dismissInstall() {
  canInstall.value = false
  window.localStorage.setItem(INSTALL_DISMISS_KEY, String(Date.now()))
}

function dismissOfflineReady() {
  offlineReady.value = false
}

async function reload() {
  await updateServiceWorker(true)
}

onMounted(() => {
  window.addEventListener('beforeinstallprompt', onBeforeInstallPrompt)
  window.addEventListener('appinstalled', onAppInstalled)
  if (isStandalone()) canInstall.value = false
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeinstallprompt', onBeforeInstallPrompt)
  window.removeEventListener('appinstalled', onAppInstalled)
  if (updateTimer !== undefined) window.clearInterval(updateTimer)
  if (offlineTimer !== undefined) window.clearTimeout(offlineTimer)
})
</script>

<template>
  <div class="pwa-prompt">
    <div v-if="needRefresh" class="card" role="status">
      <span class="text">发现新版本，刷新后生效</span>
      <UButton size="sm" label="立即刷新" @click="reload" />
    </div>
    <div v-else-if="showInstallPrompt" class="card" role="status">
      <span class="text">安装到桌面，像应用一样使用</span>
      <UButton size="sm" label="安装" @click="install" />
      <UButton size="sm" color="neutral" variant="ghost" label="以后再说" @click="dismissInstall" />
    </div>
    <div v-else-if="offlineReady" class="card" role="status">
      <span class="text">已缓存应用外壳，断网也能打开</span>
      <UButton size="sm" color="neutral" variant="ghost" label="知道了" @click="dismissOfflineReady" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.pwa-prompt {
  position: fixed;
  left: max(16px, env(safe-area-inset-left));
  right: auto;
  bottom: max(24px, env(safe-area-inset-bottom));
  z-index: 3000;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: var(--ui-bg);
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  box-shadow: var(--el-box-shadow);
  font-size: 13px;
  color: var(--ui-text);

  .text {
    white-space: nowrap;
    text-wrap: pretty;
  }
}

@media (max-width: 480px) {
  .pwa-prompt {
    left: max(16px, env(safe-area-inset-left));
    right: max(16px, env(safe-area-inset-right));
  }

  .card {
    flex-wrap: wrap;

    .text {
      white-space: normal;
    }
  }
}
</style>

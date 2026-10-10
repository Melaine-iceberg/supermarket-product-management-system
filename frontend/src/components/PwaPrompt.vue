<script setup lang="ts">
/**
 * PWA 交互入口：
 * 1) 监听 beforeinstallprompt，提供「安装到桌面」入口；
 * 2) 通过 useRegisterSW 检测新版本，提示用户刷新以更新应用外壳。
 * 离线能力仅覆盖应用外壳（静态资源），接口请求始终联网，故此处不缓存业务数据。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
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
      <el-button type="primary" size="small" @click="reload">立即刷新</el-button>
    </div>
    <div v-else-if="canInstall" class="card" role="status">
      <span class="text">安装到桌面，像应用一样使用</span>
      <el-button type="primary" size="small" @click="install">安装</el-button>
      <el-button text size="small" @click="dismissInstall">以后再说</el-button>
    </div>
    <div v-else-if="offlineReady" class="card" role="status">
      <span class="text">已缓存应用外壳，断网也能打开</span>
      <el-button text size="small" @click="dismissOfflineReady">知道了</el-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.pwa-prompt {
  position: fixed;
  right: 16px;
  bottom: 16px;
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
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  box-shadow: 0 6px 16px rgb(0 0 0 / 12%);
  font-size: 13px;
  color: #303133;

  .text {
    white-space: nowrap;
  }
}
</style>

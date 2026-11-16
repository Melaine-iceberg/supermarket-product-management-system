<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { notify } from '@/utils/notify'
import { useAuthStore } from '@/stores/auth'
import { fetchDashboardTodos, type DashboardTodo } from '@/api/report'
import { useColorScheme } from '@/composables/useColorScheme'
import NavMenu from './NavMenu.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)

const { resolved: colorScheme, toggle: toggleColorScheme } = useColorScheme()

const storeName = '超市商品管理系统'
const user = computed(() => auth.user)

/** 面包屑：系统管理 / 用户管理 */
const breadcrumbs = computed(() =>
  route.matched
    .filter((r) => r.meta?.title)
    .map((r) => ({ label: r.meta.title as string, to: r.path })),
)

// ---------- 顶部导航：全屏切换（IR-UI-02/GAP-P2-25） ----------
const fullscreen = ref(false)

function onFullscreenChange() {
  fullscreen.value = !!document.fullscreenElement
}

async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    } else {
      await document.documentElement.requestFullscreen()
    }
  } catch {
    notify.warning('当前浏览器不支持全屏切换或请求被拒绝')
  }
}

// ---------- 顶部导航：待办消息（IR-UI-02/GAP-P2-25，按权限返回数量） ----------
const todos = ref<DashboardTodo[]>([])
const todoTotal = computed(() => todos.value.reduce((s, t) => s + t.count, 0))

async function loadTodos() {
  try {
    todos.value = await fetchDashboardTodos()
  } catch {
    // 拦截器已提示；待办保持原值
  }
}

function openTodo(t: DashboardTodo) {
  router.push(t.route)
}

// ---------- 响应式侧边栏：窄屏改为抽屉，避免挤压表格 ----------
const narrowQuery = window.matchMedia('(max-width: 768px)')
const isNarrow = ref(narrowQuery.matches)
const mobileMenuOpen = ref(false)
const sidebarExpanded = computed(() =>
  isNarrow.value ? mobileMenuOpen.value : !collapsed.value,
)

function toggleSidebar() {
  if (isNarrow.value) mobileMenuOpen.value = !mobileMenuOpen.value
  else collapsed.value = !collapsed.value
}

function onNarrowChange(event: MediaQueryListEvent) {
  isNarrow.value = event.matches
  if (!event.matches) mobileMenuOpen.value = false
}

// 路由变化后自动关闭移动端抽屉
watch(
  () => route.path,
  () => {
    mobileMenuOpen.value = false
  },
)

onMounted(() => {
  document.addEventListener('fullscreenchange', onFullscreenChange)
  narrowQuery.addEventListener('change', onNarrowChange)
  loadTodos()
})

onUnmounted(() => {
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  narrowQuery.removeEventListener('change', onNarrowChange)
})

async function handleLogout() {
  await auth.logout()
  router.push({ name: 'login' })
}

const userMenuItems = computed(() => [
  [
    {
      label: '修改密码',
      icon: 'i-lucide-lock',
      onSelect: (): void => {
        router.push({ name: 'change-password' })
      },
    },
    {
      label: colorScheme.value === 'dark' ? '切换到浅色模式' : '切换到深色模式',
      icon: colorScheme.value === 'dark' ? 'i-lucide-sun' : 'i-lucide-moon',
      onSelect: (): void => {
        toggleColorScheme()
      },
    },
  ],
  [
    {
      label: '退出登录',
      icon: 'i-lucide-log-out',
      color: 'error' as const,
      onSelect: (): void => {
        void handleLogout()
      },
    },
  ],
])
</script>

<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>

  <div class="flex h-dvh overflow-hidden bg-default">
    <aside
      v-if="!isNarrow"
      id="app-sidebar"
      class="flex shrink-0 flex-col overflow-hidden border-r border-default bg-default transition-[width] duration-200"
      :class="collapsed ? 'w-16' : 'w-56'"
    >
      <router-link
        to="/home"
        class="flex h-14 shrink-0 items-center justify-center gap-2 border-b border-default text-default no-underline"
        aria-label="返回首页"
      >
        <UIcon name="i-lucide-shopping-cart" class="size-6 shrink-0 text-primary" />
        <span v-show="!collapsed" class="truncate text-sm font-bold">{{ storeName }}</span>
      </router-link>
      <div class="flex-1 overflow-y-auto py-2">
        <NavMenu :collapsed="collapsed" :active-path="route.path" />
      </div>
    </aside>

    <!-- 窄屏：同一套菜单放入抽屉 -->
    <AppDrawer
      v-else
      id="app-mobile-nav"
      v-model="mobileMenuOpen"
      :with-header="false"
      direction="ltr"
      size="240px"
    >
      <router-link
        to="/home"
        class="flex h-14 shrink-0 items-center justify-center gap-2 border-b border-default text-default no-underline"
        aria-label="返回首页"
        @click="mobileMenuOpen = false"
      >
        <UIcon name="i-lucide-shopping-cart" class="size-6 text-primary" />
        <span class="truncate text-sm font-bold">{{ storeName }}</span>
      </router-link>
      <div class="py-2">
        <NavMenu
          :collapsed="false"
          :active-path="route.path"
          @select="mobileMenuOpen = false"
        />
      </div>
    </AppDrawer>

    <div class="flex min-w-0 flex-1 flex-col">
      <header
        class="flex h-14 shrink-0 items-center justify-between gap-2 border-b border-default bg-default px-3"
      >
        <div class="flex min-w-0 items-center gap-3">
          <UButton
            color="neutral"
            variant="ghost"
            square
            :icon="!isNarrow && !collapsed ? 'i-lucide-panel-left-close' : 'i-lucide-panel-left-open'"
            :aria-label="sidebarExpanded ? '收起侧边栏' : '展开侧边栏'"
            :aria-expanded="sidebarExpanded"
            :aria-controls="isNarrow ? 'app-mobile-nav' : 'app-sidebar'"
            @click="toggleSidebar"
          />
          <UBreadcrumb :items="breadcrumbs" class="app-breadcrumb min-w-0" />
        </div>

        <div class="header-actions flex shrink-0 items-center gap-1">
          <!-- 待办消息（IR-UI-02）：按登录用户权限返回待办数量，点击条目跳转处理页 -->
          <UPopover :content="{ align: 'end' }" @update:open="(open) => open && loadTodos()">
            <UButton
              color="neutral"
              variant="ghost"
              square
              icon="i-lucide-bell"
              class="todo-trigger relative"
              aria-label="待办事项"
            >
              <UBadge
                v-if="todoTotal > 0"
                :label="todoTotal > 99 ? '99+' : String(todoTotal)"
                color="error"
                size="sm"
                class="absolute -top-1 -right-1"
              />
            </UButton>

            <template #content>
              <div class="todo-list w-72 p-2">
                <div v-if="todos.length === 0" class="py-4 text-center text-sm text-muted">
                  暂无待办事项
                </div>
                <button
                  v-for="t in todos"
                  :key="t.type"
                  type="button"
                  class="flex w-full items-center justify-between gap-3 rounded-md px-2 py-2 text-left text-sm transition-colors hover:bg-elevated"
                  @click="openTodo(t)"
                >
                  <span>{{ t.title }}</span>
                  <UBadge
                    :color="t.count > 0 ? 'error' : 'neutral'"
                    variant="soft"
                    size="sm"
                  >
                    {{ t.count }}
                  </UBadge>
                </button>
              </div>
            </template>
          </UPopover>

          <!-- 全屏切换（IR-UI-02） -->
          <UTooltip :text="fullscreen ? '退出全屏' : '全屏切换'">
            <UButton
              color="neutral"
              variant="ghost"
              square
              icon="i-lucide-maximize"
              class="header-icon"
              :aria-label="fullscreen ? '退出全屏' : '进入全屏'"
              @click="toggleFullscreen"
            />
          </UTooltip>

          <UDropdownMenu :items="userMenuItems" :content="{ align: 'end' }">
            <UButton color="neutral" variant="ghost" class="user-info gap-2">
              <UAvatar :text="user?.realName?.charAt(0)" size="sm" />
              <span class="user-name hidden text-sm sm:inline">{{ user?.realName }}</span>
              <UBadge variant="outline" size="sm" class="role-tag hidden md:inline-flex">
                {{ user?.roleNames?.[0] || '未分配角色' }}
              </UBadge>
              <UIcon name="i-lucide-chevron-down" class="size-4 text-dimmed" />
            </UButton>
          </UDropdownMenu>
        </div>
      </header>

      <main id="main-content" class="flex-1 overflow-y-auto bg-muted p-4" tabindex="-1">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped lang="scss">
.skip-link {
  position: absolute;
  top: -48px;
  left: 8px;
  z-index: 4000;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--ui-bg);
  color: var(--ui-primary);
  box-shadow: var(--el-box-shadow-light);
  text-decoration: none;
  transition: top 0.2s;

  &:focus {
    top: 8px;
  }
}
</style>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { iconName } from '@/utils/icons'

const props = defineProps<{
  collapsed: boolean
  activePath: string
}>()

const emit = defineEmits<{ select: [] }>()
const auth = useAuthStore()

const openDirs = reactive<Record<number, boolean>>({})

function isActive(path?: string | null) {
  if (!path) return false
  return props.activePath === path || props.activePath.startsWith(`${path}/`)
}

function syncOpenDirs() {
  for (const dir of auth.menus) {
    if (dir.children?.some((menu) => isActive(menu.routePath))) {
      openDirs[dir.id] = true
    } else if (!(dir.id in openDirs)) {
      openDirs[dir.id] = false
    }
  }
}

watch(() => props.activePath, syncOpenDirs, { immediate: true })

function dropdownItems(dir: { children?: { id: number; name: string; icon?: string | null; routePath?: string | null }[] }) {
  return (dir.children ?? []).map((menu) => ({
    label: menu.name,
    icon: iconName(menu.icon ?? undefined),
    to: menu.routePath ?? undefined,
    onSelect: () => emit('select'),
  }))
}
</script>

<template>
  <!-- 动态菜单（FR-SYS-05：按角色权限渲染） -->
  <nav class="menu space-y-0.5 px-2" aria-label="主菜单">
    <template v-for="dir in auth.menus" :key="dir.id">
      <!-- 展开态：可折叠目录 -->
      <UCollapsible
        v-if="dir.children?.length && !collapsed"
        v-model:open="openDirs[dir.id]"
        :unmount-on-hide="false"
      >
        <UButton
          color="neutral"
          variant="ghost"
          class="w-full justify-between"
          :aria-label="dir.name"
        >
          <span class="flex min-w-0 items-center gap-2">
            <AppIcon :name="dir.icon ?? undefined" :size="18" class="shrink-0" />
            <span class="truncate">{{ dir.name }}</span>
          </span>
          <UIcon
            name="i-lucide-chevron-down"
            class="size-4 shrink-0 text-dimmed transition-transform"
            :class="{ 'rotate-180': openDirs[dir.id] }"
          />
        </UButton>

        <template #content>
          <div class="ms-3 mt-1 space-y-0.5 border-s border-default ps-2">
            <UButton
              v-for="menu in dir.children"
              :key="menu.id"
              :to="menu.routePath || undefined"
              color="neutral"
              :variant="isActive(menu.routePath) ? 'soft' : 'ghost'"
              :class="['w-full justify-start', isActive(menu.routePath) ? 'text-primary' : '']"
              @click="emit('select')"
            >
              <AppIcon :name="menu.icon ?? undefined" :size="16" class="shrink-0" />
              <span class="truncate">{{ menu.name }}</span>
            </UButton>
          </div>
        </template>
      </UCollapsible>

      <!-- 收起态：目录使用右侧浮层菜单，保持图标导航可用 -->
      <UDropdownMenu
        v-else-if="dir.children?.length"
        :items="dropdownItems(dir)"
        :content="{ side: 'right', align: 'start' }"
      >
        <UButton
          color="neutral"
          variant="ghost"
          class="w-full justify-center"
          :aria-label="dir.name"
        >
          <AppIcon :name="dir.icon ?? undefined" :size="20" />
        </UButton>
      </UDropdownMenu>

      <!-- 顶层菜单 -->
      <UTooltip v-else :text="dir.name">
        <UButton
          :to="dir.routePath || undefined"
          color="neutral"
          variant="ghost"
          :class="collapsed ? 'w-full justify-center' : 'w-full justify-start'"
          @click="emit('select')"
        >
          <AppIcon :name="dir.icon ?? undefined" :size="18" class="shrink-0" />
          <span v-if="!collapsed" class="truncate">{{ dir.name }}</span>
        </UButton>
      </UTooltip>
    </template>
  </nav>
</template>

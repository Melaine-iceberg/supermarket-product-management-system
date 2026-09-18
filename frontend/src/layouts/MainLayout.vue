<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)

const storeName = '超市商品管理系统'
const user = computed(() => auth.user)

/** 面包屑：系统管理 / 用户管理 */
const breadcrumbs = computed(() => {
  const matched = route.matched.filter((r) => r.meta?.title)
  return matched.map((r) => ({ title: r.meta.title as string, path: r.path }))
})

async function handleLogout() {
  await auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '220px'" class="aside">
      <div class="logo" @click="router.push('/home')">
        <el-icon :size="26" color="#409eff"><ShoppingCart /></el-icon>
        <span v-show="!collapsed" class="logo-title">{{ storeName }}</span>
      </div>
      <el-scrollbar>
        <!-- 动态菜单（FR-SYS-05：按角色权限渲染） -->
        <el-menu
          :default-active="route.path"
          :collapse="collapsed"
          :collapse-transition="false"
          router
          class="menu"
        >
          <template v-for="dir in auth.menus" :key="dir.id">
            <!-- 目录 -->
            <el-sub-menu v-if="dir.children?.length" :index="dir.routePath || String(dir.id)">
              <template #title>
                <el-icon v-if="dir.icon"><component :is="dir.icon" /></el-icon>
                <span>{{ dir.name }}</span>
              </template>
              <el-menu-item
                v-for="menu in dir.children"
                :key="menu.id"
                :index="menu.routePath || String(menu.id)"
              >
                <el-icon v-if="menu.icon"><component :is="menu.icon" /></el-icon>
                <template #title>{{ menu.name }}</template>
              </el-menu-item>
            </el-sub-menu>
            <!-- 顶层菜单 -->
            <el-menu-item v-else :index="dir.routePath || String(dir.id)">
              <el-icon v-if="dir.icon"><component :is="dir.icon" /></el-icon>
              <template #title>{{ dir.name }}</template>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" :size="20" @click="collapsed = !collapsed">
            <Fold v-if="!collapsed" />
            <Expand v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item
              v-for="crumb in breadcrumbs"
              :key="crumb.path"
              :to="crumb.path"
            >
              {{ crumb.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <el-dropdown @command="(cmd: string) => cmd === 'logout' ? handleLogout() : router.push({ name: cmd })">
          <span class="user-info">
            <el-avatar :size="30" class="avatar">{{ user?.realName?.charAt(0) }}</el-avatar>
            <span class="user-name">{{ user?.realName }}</span>
            <el-tag size="small" effect="plain" class="role-tag">
              {{ user?.roleNames?.[0] || '未分配角色' }}
            </el-tag>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="change-password">
                <el-icon><Lock /></el-icon>修改密码
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script lang="ts">
export default { name: 'MainLayout' }
</script>

<style scoped lang="scss">
.layout {
  height: 100vh;
}

.aside {
  display: flex;
  flex-direction: column;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  transition: width 0.2s;
  overflow: hidden;

  .logo {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    height: 56px;
    cursor: pointer;
    border-bottom: 1px solid #f0f0f0;

    .logo-title {
      font-size: 15px;
      font-weight: 700;
      white-space: nowrap;
    }
  }

  .menu {
    border-right: none;
    width: 100%;
  }
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  height: 56px;

  .header-left {
    display: flex;
    align-items: center;
    gap: 16px;

    .collapse-btn {
      cursor: pointer;
      color: #606266;

      &:hover {
        color: #409eff;
      }
    }
  }

  .user-info {
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    outline: none;

    .avatar {
      background: #409eff;
      color: #fff;
      font-size: 14px;
    }

    .user-name {
      font-size: 14px;
    }
  }
}

.main {
  background: #f5f7fa;
  padding: 16px;
  overflow-y: auto;
}
</style>

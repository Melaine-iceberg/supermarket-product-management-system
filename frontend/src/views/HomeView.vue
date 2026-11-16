<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const user = computed(() => auth.user)
const today = new Date().toLocaleDateString('zh-CN', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  weekday: 'long',
})

/** 快捷入口：取前 6 个可访问菜单 */
const quickLinks = computed(() => {
  const links: { title: string; path: string; icon: string }[] = []
  for (const dir of auth.menus) {
    if (dir.children?.length) {
      for (const menu of dir.children) {
        if (menu.routePath) {
          links.push({ title: menu.name, path: menu.routePath, icon: menu.icon || 'Document' })
        }
        if (links.length >= 8) return links
      }
    }
  }
  return links
})
</script>

<template>
  <div class="mx-auto w-full max-w-[1100px] space-y-4">
    <UCard>
      <div class="flex items-center gap-4">
        <UAvatar :text="user?.realName?.charAt(0)" size="3xl" />
        <div class="min-w-0">
          <h3 class="text-lg font-semibold text-default">
            {{ user?.realName }}，{{
              today.includes('星期六') || today.includes('星期日') ? '周末好' : '你好'
            }}
          </h3>
          <p class="mt-1 text-sm text-muted">
            {{ today }} · 当前角色：{{ user?.roleNames?.join('、') || '未分配' }}
          </p>
        </div>
      </div>
    </UCard>

    <UCard>
      <template #header>
        <span class="font-medium text-default">快捷入口</span>
      </template>
      <div v-if="quickLinks.length" class="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <router-link
          v-for="link in quickLinks"
          :key="link.path"
          :to="link.path"
          class="flex flex-col items-center gap-2 rounded-lg border border-default px-2 py-4 text-center text-sm text-default no-underline transition-colors hover:border-primary hover:text-primary"
        >
          <AppIcon :name="link.icon" :size="24" class="text-primary" />
          <span class="truncate">{{ link.title }}</span>
        </router-link>
      </div>
      <UEmpty
        v-else
        icon="i-lucide-layout-grid"
        title="暂无可用菜单"
        description="请联系管理员分配角色权限"
      />
    </UCard>

    <UCard>
      <template #header>
        <span class="font-medium text-default">系统说明</span>
      </template>
      <AppDescriptions :column="2" border>
        <AppDescriptionsItem label="系统名称">超市商品管理系统（SMMS）</AppDescriptionsItem>
        <AppDescriptionsItem label="版本">
          v0.1.0 · 全模块交付（系统 / 商品 / 库存 / 采购 / 销售 / 会员 / 报表）
        </AppDescriptionsItem>
        <AppDescriptionsItem label="技术栈">
          前端 Vue 3 + Nuxt UI + Tailwind CSS；后端 Spring Boot 4 + MyBatis-Plus
        </AppDescriptionsItem>
        <AppDescriptionsItem label="接口规范">
          RESTful 风格（/api/v1），JWT Bearer 认证
        </AppDescriptionsItem>
      </AppDescriptions>
      <UAlert
        class="mt-4"
        color="info"
        variant="soft"
        icon="i-lucide-info"
        title="Must 级需求已全部上线；少量中优先级（P2，如商品编码规则细化、促销并发兜底、供应商/会员细节、性能与覆盖率证据等）与 Could 级增强（自定义报表、分类经营概览、供应商绩效、会员人工调整、系统监控、皮重录入、库存日快照等）尚未实现，逐条清单与取舍已整理为论文“可扩展性/未来工作”素材（《需求实现差距分析报告》批次六末表）"
      />
    </UCard>
  </div>
</template>

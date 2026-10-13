<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
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
  <div class="home">
    <el-row :gutter="16">
      <el-col :span="24">
        <el-card>
          <div class="welcome">
            <el-avatar :size="56" class="avatar">{{ user?.realName?.charAt(0) }}</el-avatar>
            <div class="info">
              <h3>{{ user?.realName }}，{{ today.includes('星期六') || today.includes('星期日') ? '周末好' : '你好' }}</h3>
              <p>{{ today }} · 当前角色：{{ user?.roleNames?.join('、') || '未分配' }}</p>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="24">
        <el-card>
          <template #header>快捷入口</template>
          <div v-if="quickLinks.length" class="links">
            <div
              v-for="link in quickLinks"
              :key="link.path"
              class="link-item"
              @click="router.push(link.path)"
            >
              <el-icon :size="24" color="#409eff"><component :is="link.icon" /></el-icon>
              <span>{{ link.title }}</span>
            </div>
          </div>
          <el-empty v-else description="暂无可用菜单" :image-size="60" />
        </el-card>
      </el-col>

      <el-col :span="24">
        <el-card>
          <template #header>系统说明</template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="系统名称">超市商品管理系统（SMMS）</el-descriptions-item>
            <el-descriptions-item label="版本">v0.1.0 · 全模块交付（系统 / 商品 / 库存 / 采购 / 销售 / 会员 / 报表）</el-descriptions-item>
            <el-descriptions-item label="技术栈">前端 Vue 3 + Element Plus；后端 Spring Boot 4 + MyBatis-Plus</el-descriptions-item>
            <el-descriptions-item label="接口规范">RESTful 风格（/api/v1），JWT Bearer 认证</el-descriptions-item>
          </el-descriptions>
          <el-alert
            class="tip"
            type="info"
            :closable="false"
            show-icon
            title="Must 级需求已全部上线；少量中优先级（P2，如商品编码规则细化、促销并发兜底、供应商/会员细节、性能与覆盖率证据等）与 Could 级增强（自定义报表、分类经营概览、供应商绩效、会员人工调整、系统监控、皮重录入、库存日快照等）尚未实现，逐条清单与取舍已整理为论文“可扩展性/未来工作”素材（《需求实现差距分析报告》批次六末表）"
          />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped lang="scss">
.home {
  max-width: 1100px;
  margin: 0 auto;

  .welcome {
    display: flex;
    align-items: center;
    gap: 16px;

    .avatar {
      background: #409eff;
      color: #fff;
      font-size: 22px;
      flex-shrink: 0;
    }

    .info {
      h3 {
        margin: 0 0 6px;
        font-size: 18px;
      }

      p {
        margin: 0;
        color: #909399;
        font-size: 13px;
      }
    }
  }

  .links {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 12px;

    .link-item {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 18px 8px;
      border: 1px solid #ebeef5;
      border-radius: 8px;
      cursor: pointer;
      transition: all 0.2s;
      font-size: 13px;

      &:hover {
        border-color: #409eff;
        color: #409eff;
        box-shadow: 0 2px 12px rgba(64, 158, 255, 0.15);
      }
    }
  }

  .tip {
    margin-top: 16px;
  }
}
</style>

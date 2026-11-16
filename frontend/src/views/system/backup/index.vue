<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm } from '@/utils/confirm'
import { onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  pageBackupRecords,
  triggerBackup,
  type BackupRecordVO,
} from '@/api/system'

const auth = useAuthStore()

const loading = ref(false)
const backing = ref(false)
const total = ref(0)
const list = ref<BackupRecordVO[]>([])
const query = reactive({ page: 1, size: 10 })

async function load() {
  loading.value = true
  try {
    const data = await pageBackupRecords({ page: query.page, size: query.size })
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

/** 一键触发全量备份（仅超级管理员；保留最近 30 份） */
async function doBackup() {
  try {
    await confirm(
      '立即执行数据库全量备份？数据量较大时需等待数十秒至数分钟；备份文件保留最近 30 份，历史记录保留可查。',
      '一键触发备份',
      { type: 'warning', confirmButtonText: '开始备份' },
    )
  } catch {
    return
  }
  backing.value = true
  try {
    const record = await triggerBackup()
    if (record.status === 'SUCCESS') {
      notify.success(`备份完成：${record.fileName}（${humanSize(record.fileSize)}）`)
    } else {
      notify.error(`备份失败：${record.errorMessage || '未知原因'}`)
    }
    query.page = 1
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    backing.value = false
  }
}

function humanSize(bytes: number | null | undefined) {
  if (bytes == null) return '—'
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

function fmtDuration(ms: number | null | undefined) {
  if (ms == null) return '—'
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

onMounted(load)
</script>

<template>
  <div class="page">
    <UAlert
      color="info"
      icon="i-lucide-info"
      title="每日 23:30 自动全量备份（mysqldump），保留最近 30 份；也可一键触发补备。首次部署请按《部署与运维手册》§5.1 配置并演练恢复流程。"
      class="tip"
    />
    <UCard>
      <template #header>
        <div class="card-header">
          <span>备份记录（FR-SYS-10）</span>
          <UButton
            v-if="auth.hasPerm('sys:backup:run')"
            :loading="backing"
            :label="backing ? '备份中…' : '一键触发备份'"
            @click="doBackup"
          />
        </div>
      </template>
      <AppTable :data="list" :loading="loading" border stripe>
        <AppTableColumn prop="fileName" label="备份文件" min-width="220" show-overflow-tooltip />
        <AppTableColumn label="大小" width="100">
          <template #default="{ row }">{{ humanSize(row.fileSize) }}</template>
        </AppTableColumn>
        <AppTableColumn label="触发" width="110">
          <template #default="{ row }">
            <UBadge
              size="sm"
              :color="row.triggerType === 'MANUAL' ? 'warning' : 'neutral'"
              variant="soft"
            >
              {{ row.triggerType === 'MANUAL' ? '一键触发' : '每日定时' }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="triggerBy" label="操作人" width="110">
          <template #default="{ row }">{{ row.triggerBy ?? '—' }}</template>
        </AppTableColumn>
        <AppTableColumn label="结果" width="90">
          <template #default="{ row }">
            <UBadge :color="row.status === 'SUCCESS' ? 'success' : 'error'" size="sm" variant="soft">
              {{ row.status === 'SUCCESS' ? '成功' : '失败' }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="startedAt" label="开始时间" width="170" />
        <AppTableColumn label="耗时" width="90">
          <template #default="{ row }">{{ fmtDuration(row.durationMs) }}</template>
        </AppTableColumn>
        <AppTableColumn label="失败原因" min-width="180">
          <template #default="{ row }">
            <span v-if="row.errorMessage" class="error">{{ row.errorMessage }}</span>
            <span v-else>—</span>
          </template>
        </AppTableColumn>
      </AppTable>
      <AppPagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @change="load"
      />
    </UCard>
  </div>
</template>

<style scoped lang="scss">
.page {
  .tip {
    margin-bottom: 12px;
  }

  .card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .error {
    color: var(--el-color-danger);
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }
}
</style>

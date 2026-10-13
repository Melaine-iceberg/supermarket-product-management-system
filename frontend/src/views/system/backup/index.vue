<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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
    await ElMessageBox.confirm(
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
      ElMessage.success(`备份完成：${record.fileName}（${humanSize(record.fileSize)}）`)
    } else {
      ElMessage.error(`备份失败：${record.errorMessage || '未知原因'}`)
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
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="每日 23:30 自动全量备份（mysqldump），保留最近 30 份；也可一键触发补备。首次部署请按《部署与运维手册》§5.1 配置并演练恢复流程。"
      class="tip"
    />
    <el-card>
      <template #header>
        <div class="card-header">
          <span>备份记录（FR-SYS-10）</span>
          <el-button
            v-if="auth.hasPerm('sys:backup:run')"
            type="primary"
            :loading="backing"
            @click="doBackup"
          >
            {{ backing ? '备份中…' : '一键触发备份' }}
          </el-button>
        </div>
      </template>
      <el-table v-loading="loading" border :data="list" stripe>
        <el-table-column prop="fileName" label="备份文件" min-width="220" show-overflow-tooltip />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ humanSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="触发" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.triggerType === 'MANUAL' ? 'warning' : 'info'">
              {{ row.triggerType === 'MANUAL' ? '一键触发' : '每日定时' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="triggerBy" label="操作人" width="110">
          <template #default="{ row }">{{ row.triggerBy ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small">
              {{ row.status === 'SUCCESS' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="startedAt" label="开始时间" width="170" />
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ fmtDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="180">
          <template #default="{ row }">
            <span v-if="row.errorMessage" class="error">{{ row.errorMessage }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @change="load"
      />
    </el-card>
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

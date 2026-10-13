<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  listScheduledTasks,
  runScheduledTask,
  pageScheduledTaskLogs,
  type ScheduledTaskVO,
  type ScheduledTaskLogVO,
} from '@/api/system'

const auth = useAuthStore()

const loading = ref(false)
const tasks = ref<ScheduledTaskVO[]>([])
const runningCode = ref('')

const logs = reactive({
  taskCode: '',
  status: '',
  page: 1,
  size: 10,
  total: 0,
  list: [] as ScheduledTaskLogVO[],
  loading: false,
})

async function loadTasks() {
  loading.value = true
  try {
    tasks.value = await listScheduledTasks()
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  logs.loading = true
  try {
    const data = await pageScheduledTaskLogs({
      taskCode: logs.taskCode || undefined,
      status: logs.status || undefined,
      page: logs.page,
      size: logs.size,
    })
    logs.list = data.list
    logs.total = data.total
  } finally {
    logs.loading = false
  }
}

function resetLogQuery() {
  logs.taskCode = ''
  logs.status = ''
  logs.page = 1
  loadLogs()
}

/** 手工触发补跑（NFR-REL-07）：执行结果入日志表，失败含原因 */
async function confirmRun(task: ScheduledTaskVO) {
  try {
    await ElMessageBox.confirm(
      `确定立即执行「${task.name}」？任务幂等，补跑不会产生重复数据；执行结果将记录在执行日志中。`,
      '手工触发任务',
      { type: 'warning', confirmButtonText: '立即执行' },
    )
  } catch {
    return
  }
  runningCode.value = task.code
  try {
    const log = await runScheduledTask(task.code)
    if (log.status === 'SUCCESS') {
      ElMessage.success(`执行成功：${log.resultSummary || '完成'}（耗时 ${fmtDuration(log.durationMs)}）`)
    } else {
      ElMessage.error(`执行失败：${log.errorMessage || '未知原因'}`)
    }
    await Promise.all([loadTasks(), loadLogs()])
  } catch {
    // 拦截器已提示
  } finally {
    runningCode.value = ''
  }
}

function fmtDuration(ms: number | null | undefined) {
  if (ms == null) return '—'
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

function statusTag(status: string | null) {
  if (status === 'SUCCESS') return { type: 'success' as const, label: '成功' }
  if (status === 'FAILED') return { type: 'danger' as const, label: '失败' }
  return { type: 'info' as const, label: '未执行' }
}

onMounted(() => {
  loadTasks()
  loadLogs()
})
</script>

<template>
  <div class="page">
    <el-card>
      <template #header>
        定时任务一览（IR-SW-06：执行结果与耗时入日志，失败自动重试 2 次；任务幂等，支持手工补跑）
      </template>
      <el-table v-loading="loading" border :data="tasks" stripe>
        <el-table-column prop="name" label="任务" width="150" />
        <el-table-column prop="code" label="代码" width="200" />
        <el-table-column prop="cron" label="调度（cron）" width="140" />
        <el-table-column prop="description" label="说明" min-width="260" show-overflow-tooltip />
        <el-table-column label="最近执行" width="170">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.lastStatus).type" size="small">
              {{ statusTag(row.lastStatus).label }}
            </el-tag>
            <span class="time">{{ row.lastStartedAt ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ fmtDuration(row.lastDurationMs) }}</template>
        </el-table-column>
        <el-table-column label="结果摘要" min-width="220">
          <template #default="{ row }">
            <span v-if="row.lastError" class="error">{{ row.lastError }}</span>
            <span v-else>{{ row.lastResultSummary || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.hasPerm('sys:sched:run')" label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              plain
              :loading="runningCode === row.code"
              @click="confirmRun(row as ScheduledTaskVO)"
            >
              立即执行
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="log-card">
      <template #header>执行日志（定时触发与手工补跑同表记录）</template>
      <el-form inline @submit.prevent>
        <el-form-item label="任务">
          <el-select v-model="logs.taskCode" clearable placeholder="全部" style="width: 220px">
            <el-option v-for="t in tasks" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="logs.status" clearable placeholder="全部" style="width: 110px">
            <el-option label="成功" value="SUCCESS" />
            <el-option label="失败" value="FAILED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="logs.page = 1; loadLogs()">查询</el-button>
          <el-button @click="resetLogQuery">重置</el-button>
        </el-form-item>
      </el-form>
      <el-table v-loading="logs.loading" :data="logs.list" stripe>
        <el-table-column prop="startedAt" label="开始时间" width="170" />
        <el-table-column prop="taskName" label="任务" width="140" />
        <el-table-column label="触发" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.triggerType === 'MANUAL' ? 'warning' : 'info'">
              {{ row.triggerType === 'MANUAL' ? '手工补跑' : '定时' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="triggerBy" label="触发人" width="110">
          <template #default="{ row }">{{ row.triggerBy ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small">
              {{ row.status === 'SUCCESS' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ fmtDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column prop="retryCount" label="重试" width="70" />
        <el-table-column label="摘要 / 失败原因" min-width="240">
          <template #default="{ row }">
            <span v-if="row.errorMessage" class="error">{{ row.errorMessage }}</span>
            <span v-else>{{ row.resultSummary || '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="logs.page"
        v-model:page-size="logs.size"
        :total="logs.total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @change="loadLogs"
      />
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.page {
  .log-card {
    margin-top: 12px;
  }

  .time {
    margin-left: 8px;
    font-size: 12px;
    color: #909399;
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

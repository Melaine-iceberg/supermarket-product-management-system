<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm } from '@/utils/confirm'
import { onMounted, reactive, ref } from 'vue'
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
    await confirm(
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
      notify.success(`执行成功：${log.resultSummary || '完成'}（耗时 ${fmtDuration(log.durationMs)}）`)
    } else {
      notify.error(`执行失败：${log.errorMessage || '未知原因'}`)
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
  if (status === 'SUCCESS') return { color: 'success' as const, label: '成功' }
  if (status === 'FAILED') return { color: 'error' as const, label: '失败' }
  return { color: 'neutral' as const, label: '未执行' }
}

onMounted(() => {
  loadTasks()
  loadLogs()
})
</script>

<template>
  <div class="page">
    <UCard>
      <template #header>
        定时任务一览（IR-SW-06：执行结果与耗时入日志，失败自动重试 2 次；任务幂等，支持手工补跑）
      </template>
      <AppTable :data="tasks" :loading="loading" border stripe>
        <AppTableColumn prop="name" label="任务" width="150" />
        <AppTableColumn prop="code" label="代码" width="200" />
        <AppTableColumn prop="cron" label="调度（cron）" width="140" />
        <AppTableColumn prop="description" label="说明" min-width="260" show-overflow-tooltip />
        <AppTableColumn label="最近执行" width="170">
          <template #default="{ row }">
            <UBadge :color="statusTag(row.lastStatus).color" size="sm" variant="soft">
              {{ statusTag(row.lastStatus).label }}
            </UBadge>
            <span class="time">{{ row.lastStartedAt ?? '—' }}</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="耗时" width="90">
          <template #default="{ row }">{{ fmtDuration(row.lastDurationMs) }}</template>
        </AppTableColumn>
        <AppTableColumn label="结果摘要" min-width="220">
          <template #default="{ row }">
            <span v-if="row.lastError" class="error">{{ row.lastError }}</span>
            <span v-else>{{ row.lastResultSummary || '—' }}</span>
          </template>
        </AppTableColumn>
        <AppTableColumn v-if="auth.hasPerm('sys:sched:run')" label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <UButton
              size="sm"
              variant="outline"
              label="立即执行"
              :loading="runningCode === row.code"
              @click="confirmRun(row as ScheduledTaskVO)"
            />
          </template>
        </AppTableColumn>
      </AppTable>
    </UCard>

    <UCard class="log-card">
      <template #header>执行日志（定时触发与手工补跑同表记录）</template>
      <AppForm inline @submit.prevent>
        <AppFormItem label="任务">
          <AppSelect v-model="logs.taskCode" clearable placeholder="全部" style="width: 220px">
            <AppOption v-for="t in tasks" :key="t.code" :label="t.name" :value="t.code" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="结果">
          <AppSelect v-model="logs.status" clearable placeholder="全部" style="width: 110px">
            <AppOption label="成功" value="SUCCESS" />
            <AppOption label="失败" value="FAILED" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem>
          <UButton label="查询" @click="logs.page = 1; loadLogs()" />
          <UButton color="neutral" variant="outline" label="重置" @click="resetLogQuery" />
        </AppFormItem>
      </AppForm>
      <AppTable :data="logs.list" :loading="logs.loading" stripe>
        <AppTableColumn prop="startedAt" label="开始时间" width="170" />
        <AppTableColumn prop="taskName" label="任务" width="140" />
        <AppTableColumn label="触发" width="110">
          <template #default="{ row }">
            <UBadge
              size="sm"
              :color="row.triggerType === 'MANUAL' ? 'warning' : 'neutral'"
              variant="soft"
            >
              {{ row.triggerType === 'MANUAL' ? '手工补跑' : '定时' }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="triggerBy" label="触发人" width="110">
          <template #default="{ row }">{{ row.triggerBy ?? '—' }}</template>
        </AppTableColumn>
        <AppTableColumn label="结果" width="90">
          <template #default="{ row }">
            <UBadge :color="row.status === 'SUCCESS' ? 'success' : 'error'" size="sm" variant="soft">
              {{ row.status === 'SUCCESS' ? '成功' : '失败' }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="耗时" width="90">
          <template #default="{ row }">{{ fmtDuration(row.durationMs) }}</template>
        </AppTableColumn>
        <AppTableColumn prop="retryCount" label="重试" width="70" />
        <AppTableColumn label="摘要 / 失败原因" min-width="240">
          <template #default="{ row }">
            <span v-if="row.errorMessage" class="error">{{ row.errorMessage }}</span>
            <span v-else>{{ row.resultSummary || '—' }}</span>
          </template>
        </AppTableColumn>
      </AppTable>
      <AppPagination
        v-model:current-page="logs.page"
        v-model:page-size="logs.size"
        :total="logs.total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @change="loadLogs"
      />
    </UCard>
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
    color: var(--el-text-color-secondary);
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

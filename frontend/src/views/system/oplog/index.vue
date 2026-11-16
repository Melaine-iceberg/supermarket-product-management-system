<script setup lang="ts">
import { notify } from '@/utils/notify'
import { onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { saveBlob } from '@/api/request'
import {
  pageOpLogs,
  listOpLogModules,
  exportOpLogs,
  type OperationLogVO,
} from '@/api/system'

const auth = useAuthStore()

const loading = ref(false)
const list = ref<OperationLogVO[]>([])
const total = ref(0)
const modules = ref<string[]>([])
const exporting = ref(false)

const query = reactive({
  username: '',
  module: '',
  result: '' as number | string,
  range: null as [string, string] | null,
  page: 1,
  size: 10,
})

function params() {
  return {
    username: query.username || undefined,
    module: query.module || undefined,
    result: query.result === '' ? undefined : query.result,
    beginDate: query.range?.[0],
    endDate: query.range?.[1],
    page: query.page,
    size: query.size,
  }
}

async function load() {
  loading.value = true
  try {
    const data = await pageOpLogs(params())
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  Object.assign(query, { username: '', module: '', result: '', range: null, page: 1 })
  load()
}

async function handleExport() {
  exporting.value = true
  try {
    const { page: _p, size: _s, ...filters } = params()
    const blob = await exportOpLogs(filters)
    saveBlob(blob, `操作日志_${new Date().toISOString().slice(0, 10)}.csv`)
    notify.success('导出成功')
  } catch {
    // 拦截器已提示
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  load()
  listOpLogModules().then((data) => (modules.value = data))
})
</script>

<template>
  <div class="page">
    <UCard class="search-card" :ui="{ body: 'pb-2' }">
      <AppForm inline @submit.prevent>
        <AppFormItem label="操作人">
          <AppInput v-model="query.username" placeholder="用户名" clearable />
        </AppFormItem>
        <AppFormItem label="模块">
          <AppSelect v-model="query.module" clearable placeholder="全部" style="width: 150px">
            <AppOption v-for="m in modules" :key="m" :label="m" :value="m" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="结果">
          <AppSelect v-model="query.result" clearable placeholder="全部" style="width: 110px">
            <AppOption label="成功" :value="1" />
            <AppOption label="失败" :value="0" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="时间">
          <AppDatePicker
            v-model="query.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
          />
        </AppFormItem>
        <AppFormItem>
          <UButton label="查询" @click="query.page = 1; load()" />
          <UButton color="neutral" variant="outline" label="重置" @click="resetQuery" />
          <UButton
            v-if="auth.hasPerm('sys:oplog:export')"
            color="neutral"
            variant="outline"
            label="导出 CSV"
            :loading="exporting"
            @click="handleExport"
          />
        </AppFormItem>
      </AppForm>
    </UCard>

    <UCard>
      <template #header>操作日志（只增不改不删，保留 ≥ 1 年）</template>
      <AppTable :data="list" :loading="loading" border stripe>
        <AppTableColumn type="expand">
          <template #default="{ row }">
            <AppDescriptions :column="2" border size="small" class="detail">
              <AppDescriptionsItem label="请求">{{ row.httpMethod }} {{ row.requestUrl }}</AppDescriptionsItem>
              <AppDescriptionsItem label="IP / UA">
                {{ row.ip }} · {{ row.userAgent || '—' }}
              </AppDescriptionsItem>
              <AppDescriptionsItem label="参数摘要" :span="2">
                <code class="param">{{ row.paramSummary || '—' }}</code>
              </AppDescriptionsItem>
              <AppDescriptionsItem label="错误信息" :span="2">
                <span :class="{ error: row.errorMsg }">{{ row.errorMsg || '—' }}</span>
              </AppDescriptionsItem>
            </AppDescriptions>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="operateTime" label="操作时间" width="165" />
        <AppTableColumn prop="username" label="操作人" width="100" />
        <AppTableColumn label="真实姓名" width="100">
          <template #default="{ row }">{{ row.realName || '—' }}</template>
        </AppTableColumn>
        <AppTableColumn prop="roleNames" label="角色" width="110">
          <template #default="{ row }">{{ row.roleNames || '—' }}</template>
        </AppTableColumn>
        <AppTableColumn prop="module" label="模块" width="100" />
        <AppTableColumn prop="action" label="动作" width="120" />
        <AppTableColumn prop="target" label="目标" min-width="110">
          <template #default="{ row }">{{ row.target || '—' }}</template>
        </AppTableColumn>
        <AppTableColumn label="结果" width="80">
          <template #default="{ row }">
            <UBadge :color="row.result === 1 ? 'success' : 'error'" size="sm" variant="soft">
              {{ row.result === 1 ? '成功' : '失败' }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="耗时" width="90">
          <template #default="{ row }">{{ row.costMs != null ? row.costMs + ' ms' : '—' }}</template>
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
  .search-card {
    margin-bottom: 12px;
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }

  .detail {
    padding: 0 16px;

    .param {
      font-family: monospace;
      font-size: 12px;
      word-break: break-all;
    }

    .error {
      color: var(--el-color-danger);
    }
  }
}
</style>

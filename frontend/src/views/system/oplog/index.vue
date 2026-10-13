<script setup lang="ts">
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
    ElMessage.success('导出成功')
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
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="操作人">
          <el-input v-model="query.username" placeholder="用户名" clearable />
        </el-form-item>
        <el-form-item label="模块">
          <el-select v-model="query.module" clearable placeholder="全部" style="width: 150px">
            <el-option v-for="m in modules" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="query.result" clearable placeholder="全部" style="width: 110px">
            <el-option label="成功" :value="1" />
            <el-option label="失败" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间">
          <el-date-picker
            v-model="query.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
          <el-button
            v-if="auth.hasPerm('sys:oplog:export')"
            :loading="exporting"
            @click="handleExport"
          >导出 CSV</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <template #header>操作日志（只增不改不删，保留 ≥ 1 年）</template>
      <el-table v-loading="loading" border :data="list" stripe>
        <el-table-column type="expand">
          <template #default="{ row }">
            <el-descriptions :column="2" border size="small" class="detail">
              <el-descriptions-item label="请求">{{ row.httpMethod }} {{ row.requestUrl }}</el-descriptions-item>
              <el-descriptions-item label="IP / UA">
                {{ row.ip }} · {{ row.userAgent || '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="参数摘要" :span="2">
                <code class="param">{{ row.paramSummary || '—' }}</code>
              </el-descriptions-item>
              <el-descriptions-item label="错误信息" :span="2">
                <span :class="{ error: row.errorMsg }">{{ row.errorMsg || '—' }}</span>
              </el-descriptions-item>
            </el-descriptions>
          </template>
        </el-table-column>
        <el-table-column prop="operateTime" label="操作时间" width="165" />
        <el-table-column prop="username" label="操作人" width="100" />
        <el-table-column label="真实姓名" width="100">
          <template #default="{ row }">{{ row.realName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="roleNames" label="角色" width="110">
          <template #default="{ row }">{{ row.roleNames || '—' }}</template>
        </el-table-column>
        <el-table-column prop="module" label="模块" width="100" />
        <el-table-column prop="action" label="动作" width="120" />
        <el-table-column prop="target" label="目标" min-width="110">
          <template #default="{ row }">{{ row.target || '—' }}</template>
        </el-table-column>
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="row.result === 1 ? 'success' : 'danger'" size="small">
              {{ row.result === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ row.costMs != null ? row.costMs + ' ms' : '—' }}</template>
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
  .search-card {
    margin-bottom: 12px;

    :deep(.el-card__body) {
      padding-bottom: 2px;
    }
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
      color: #f56c6c;
    }
  }
}
</style>

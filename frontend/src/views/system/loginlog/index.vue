<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { pageLoginLogs, type LoginLogVO } from '@/api/system'

const loading = ref(false)
const list = ref<LoginLogVO[]>([])
const total = ref(0)

const query = reactive({
  username: '',
  status: '' as number | string,
  range: null as [string, string] | null,
  page: 1,
  size: 10,
})

async function load() {
  loading.value = true
  try {
    const data = await pageLoginLogs({
      username: query.username || undefined,
      status: query.status === '' ? undefined : query.status,
      beginDate: query.range?.[0],
      endDate: query.range?.[1],
      page: query.page,
      size: query.size,
    })
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  Object.assign(query, { username: '', status: '', range: null, page: 1 })
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="query.username" placeholder="登录账号" clearable />
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 110px">
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
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <template #header>登录日志（记录成功/失败、IP、浏览器、操作系统）</template>
      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="loginTime" label="登录时间" width="170" />
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column prop="ip" label="IP 地址" width="140">
          <template #default="{ row }">{{ row.ip || '—' }}</template>
        </el-table-column>
        <el-table-column prop="browser" label="浏览器" width="110" />
        <el-table-column prop="os" label="操作系统" width="110" />
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="180">
          <template #default="{ row }">{{ row.message || '—' }}</template>
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
}
</style>

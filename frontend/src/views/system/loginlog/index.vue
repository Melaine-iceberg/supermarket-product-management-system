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
    <UCard class="search-card" :ui="{ body: 'pb-2' }">
      <AppForm inline @submit.prevent>
        <AppFormItem label="用户名">
          <AppInput v-model="query.username" placeholder="登录账号" clearable />
        </AppFormItem>
        <AppFormItem label="结果">
          <AppSelect v-model="query.status" clearable placeholder="全部" style="width: 110px">
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
        </AppFormItem>
      </AppForm>
    </UCard>

    <UCard>
      <template #header>登录日志（记录成功/失败、IP、浏览器、操作系统）</template>
      <AppTable :data="list" :loading="loading" border stripe>
        <AppTableColumn prop="loginTime" label="登录时间" width="170" />
        <AppTableColumn prop="username" label="用户名" width="120" />
        <AppTableColumn prop="ip" label="IP 地址" width="140">
          <template #default="{ row }">{{ row.ip || '—' }}</template>
        </AppTableColumn>
        <AppTableColumn prop="browser" label="浏览器" width="110" />
        <AppTableColumn prop="os" label="操作系统" width="110" />
        <AppTableColumn label="结果" width="90">
          <template #default="{ row }">
            <UBadge :color="row.status === 1 ? 'success' : 'error'" size="sm" variant="soft">
              {{ row.status === 1 ? '成功' : '失败' }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="message" label="说明" min-width="180">
          <template #default="{ row }">{{ row.message || '—' }}</template>
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
}
</style>

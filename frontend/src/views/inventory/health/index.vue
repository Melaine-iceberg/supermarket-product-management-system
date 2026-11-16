<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm } from '@/utils/confirm'
import { onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  checkInventoryHealth,
  fixInventoryHealth,
  type InventoryHealthRow,
} from '@/api/inventory'

const auth = useAuthStore()

// ---------- 体检（FR-INV-11）：按流水重算理论结存并比对，输出差异清单 ----------
const loading = ref(false)
const list = ref<InventoryHealthRow[]>([])
const total = ref(0)
const checkedSkus = ref(0)
const query = reactive({ page: 1, size: 20 })

async function load() {
  loading.value = true
  try {
    const data = await checkInventoryHealth({ page: query.page, size: query.size })
    list.value = data.rows
    total.value = data.total
    checkedSkus.value = data.checkedSkus
  } finally {
    loading.value = false
  }
}

function fmtQty(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toString()
}

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

// ---------- 一键按流水修正（FR-INV-11，仅超级管理员） ----------
const fixing = ref(false)

async function doFix() {
  try {
    await confirm(
      `将以库存流水为准修正全部 ${total.value} 个差异商品的账面结存（结存 = 期初 + Σ流水），成本金额按当前成本单价重算；修正过程留痕不可撤销`,
      '一键按流水修正结存',
      { type: 'warning', confirmButtonText: '确认修正' },
    )
  } catch {
    return
  }
  fixing.value = true
  try {
    const fixed = await fixInventoryHealth()
    notify.success(fixed > 0 ? `修正完成，共覆盖 ${fixed} 个商品结存` : '未发现差异，无需修正')
    query.page = 1
    await load()
  } catch {
    // 拦截器已提示（无权限 / 服务端拒绝）
  } finally {
    fixing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <!-- 体检说明与汇总（FR-INV-11，AC-04） -->
    <UCard class="intro-card">
      <template #header>
        <div class="card-head">
          <span>库存体检</span>
          <div>
            <UButton
              color="neutral"
              variant="outline"
              icon="i-lucide-refresh-cw"
              label="重新体检"
              :loading="loading"
              @click="load"
            />
            <UButton
              v-if="auth.hasPerm('sys:inv:health:fix')"
              color="error"
              label="一键按流水修正结存"
              :disabled="total === 0"
              :loading="fixing"
              @click="doFix"
            />
          </div>
        </div>
      </template>

      <UAlert
        color="info"
        icon="i-lucide-info"
        title="按流水重算每个商品的理论结存（结存 = 期初 + Σ流水数量，BR-INV-08）与账面结存比对；差异清单为空即库存数据一致（AC-04）"
        class="intro-alert"
      />
      <UAlert
        v-if="!loading && total === 0"
        color="success"
        icon="i-lucide-circle-check"
        :title="`体检通过：${checkedSkus} 个商品账面结存与流水一致，无差异`"
      />
      <UAlert
        v-else-if="!loading"
        color="error"
        icon="i-lucide-circle-alert"
        :title="`发现 ${total} 个商品账面结存与流水不一致（共体检 ${checkedSkus} 个商品），请核对下方差异清单`"
      />

      <AppDescriptions v-if="!loading" :column="2" border size="small" class="stats">
        <AppDescriptionsItem label="参与体检商品数">{{ checkedSkus }}</AppDescriptionsItem>
        <AppDescriptionsItem label="差异商品数">
          <span :class="total > 0 ? 'diff-pos' : ''">{{ total }}</span>
        </AppDescriptionsItem>
      </AppDescriptions>
    </UCard>

    <!-- 差异清单 -->
    <UCard v-if="total > 0 || loading">
      <AppTable :loading="loading" border :data="list" stripe>
        <AppTableColumn prop="goodsCode" label="商品编码" width="140" />
        <AppTableColumn label="商品" min-width="200">
          <template #default="{ row }">
            <span class="goods-name">{{ row.goodsName }}</span>
            <UBadge v-if="row.rowMissing" color="warning" variant="soft" size="sm" class="missing-tag">
              结存行缺失
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="categoryName" label="分类" width="110" />
        <AppTableColumn label="账面结存" width="110" align="right">
          <template #default="{ row }">{{ fmtQty(row.bookQty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="理论结存（Σ流水）" width="150" align="right">
          <template #default="{ row }">{{ fmtQty(row.recordQty) }}</template>
        </AppTableColumn>
        <AppTableColumn label="差异" width="110" align="right">
          <template #default="{ row }">
            <span :class="row.diffQty > 0 ? 'diff-pos' : 'diff-neg'">
              {{ row.diffQty > 0 ? '+' : '' }}{{ fmtQty(row.diffQty) }}
            </span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="成本单价" width="100" align="right">
          <template #default="{ row }">{{ fmtQty(row.costPrice) }}</template>
        </AppTableColumn>
        <AppTableColumn label="最近变动时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.lastChangeTime) }}</template>
        </AppTableColumn>
      </AppTable>

      <AppPagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @change="load"
      />
    </UCard>
  </div>
</template>

<style scoped lang="scss">
.page {
  .intro-card {
    margin-bottom: 12px;
  }

  .card-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .intro-alert {
    margin-bottom: 10px;
  }

  .stats {
    margin-top: 10px;
  }

  .goods-name {
    font-weight: 500;
  }

  .missing-tag {
    margin-left: 8px;
  }

  .diff-pos {
    color: var(--el-color-danger);
    font-weight: 600;
  }

  .diff-neg {
    color: var(--el-color-warning);
    font-weight: 600;
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }
}
</style>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { saveBlob } from '@/api/request'
import { getCategoryTree, type CategoryNode } from '@/api/goods'
import { exportAlerts, pageAlerts, type AlertItemVO, type AlertSummaryVO } from '@/api/inventory'

const auth = useAuthStore()

const alertTypeMap: Record<string, { label: string; type: 'danger' | 'warning' | 'info' }> = {
  OUT: { label: '缺货', type: 'danger' },
  WARNING: { label: '预警', type: 'warning' },
  OVERSTOCK: { label: '积压', type: 'info' },
}

function qty(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toString()
}

// ---------- 查询（FR-INV-05：默认按缺口量降序，最紧急优先） ----------
const loading = ref(false)
const list = ref<AlertItemVO[]>([])
const total = ref(0)
const summary = ref<AlertSummaryVO>({ outCount: 0, warningCount: 0, overstockCount: 0 })
const query = reactive({
  name: '',
  goodsCode: '',
  categoryId: '' as number | '',
  alertType: '' as string,
  page: 1,
  size: 20,
})

/** 排序白名单（与后端 InventoryMapper 一致） */
const SORTABLE = new Set(['gap', 'quantity', 'goodsCode', 'name'])
const sort = reactive<{ by?: string; dir?: 'asc' | 'desc' }>({ by: 'gap', dir: 'desc' })

const tableRef = ref()

async function load() {
  loading.value = true
  try {
    const data = await pageAlerts({
      name: query.name.trim() || undefined,
      goodsCode: query.goodsCode.trim() || undefined,
      categoryId: query.categoryId === '' ? undefined : query.categoryId,
      alertType: query.alertType || undefined,
      page: query.page,
      size: query.size,
      sortBy: sort.by,
      sortDir: sort.dir,
    })
    list.value = data.list
    total.value = data.total
    summary.value = data.summary
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

function resetQuery() {
  Object.assign(query, { name: '', goodsCode: '', categoryId: '', alertType: '', page: 1 })
  sort.by = 'gap'
  sort.dir = 'desc'
  tableRef.value?.clearSort()
  load()
}

function onSortChange({ prop, order }: { prop: string | null; order: string | null }) {
  if (!order || !prop || !SORTABLE.has(prop)) {
    sort.by = undefined
    sort.dir = undefined
  } else {
    sort.by = prop === 'gapQty' ? 'gap' : prop
    sort.dir = order === 'ascending' ? 'asc' : 'desc'
  }
  query.page = 1
  load()
}

function pickType(t: string) {
  query.alertType = query.alertType === t ? '' : t
  query.page = 1
  load()
}

const categoryTree = ref<CategoryNode[]>([])

onMounted(() => {
  load()
  getCategoryTree().then((data) => (categoryTree.value = data))
})

// ---------- 预警清单导出（FR-INV-05：与列表同条件） ----------
const exporting = ref(false)

async function handleExport() {
  exporting.value = true
  try {
    const blob = await exportAlerts({
      name: query.name.trim() || undefined,
      goodsCode: query.goodsCode.trim() || undefined,
      categoryId: query.categoryId === '' ? undefined : query.categoryId,
      alertType: query.alertType || undefined,
      sortBy: sort.by,
      sortDir: sort.dir,
    })
    saveBlob(blob, `库存预警清单-${new Date().toISOString().slice(0, 10)}.xlsx`)
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="page">
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="商品名称">
          <el-input v-model="query.name" placeholder="名称模糊查询" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="商品编码">
          <el-input v-model="query.goodsCode" placeholder="编码模糊查询" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="分类">
          <el-tree-select
            v-model="query.categoryId"
            :data="categoryTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            check-strictly
            clearable
            placeholder="含子分类"
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-head">
          <span>库存预警</span>
          <el-button
            v-if="auth.hasPerm('sys:inv-warning:export')"
            :loading="exporting"
            @click="handleExport"
          >
            <el-icon><Download /></el-icon>导出预警清单
          </el-button>
        </div>
      </template>

      <!-- 预警类型汇总（FR-INV-05 / FR-RPT-01 看板角标同口径），点击切换筛选 -->
      <div class="tabs">
        <button
          class="tab"
          :class="{ active: query.alertType === '' }"
          @click="pickType('')"
        >
          全部 <b>{{ summary.outCount + summary.warningCount + summary.overstockCount }}</b>
        </button>
        <button class="tab" :class="{ active: query.alertType === 'OUT' }" @click="pickType('OUT')">
          <span class="dot out" />缺货 <b>{{ summary.outCount }}</b>
        </button>
        <button class="tab" :class="{ active: query.alertType === 'WARNING' }" @click="pickType('WARNING')">
          <span class="dot warn" />预警 <b>{{ summary.warningCount }}</b>
        </button>
        <button class="tab" :class="{ active: query.alertType === 'OVERSTOCK' }" @click="pickType('OVERSTOCK')">
          <span class="dot over" />积压 <b>{{ summary.overstockCount }}</b>
        </button>
      </div>

      <el-table ref="tableRef" v-loading="loading" border :data="list" stripe @sort-change="onSortChange">
        <el-table-column prop="goodsCode" label="商品编码" width="130" sortable="custom" />
        <el-table-column prop="name" label="商品名称" min-width="170" sortable="custom">
          <template #default="{ row }">
            <div class="goods-name">{{ row.name }}</div>
            <div class="sub">{{ row.barcode || '无条码' }}<template v-if="row.spec"> / {{ row.spec }}</template></div>
          </template>
        </el-table-column>
        <el-table-column prop="categoryName" label="分类" width="110" />
        <el-table-column prop="unitName" label="单位" width="60" align="center" />
        <el-table-column prop="quantity" label="结存数量" width="100" align="right" sortable="custom">
          <template #default="{ row }">
            <span class="qty" :class="{ out: row.quantity <= 0 }">{{ qty(row.quantity) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="安全下限" width="88" align="right">
          <template #default="{ row }">{{ qty(row.safetyStockLower) }}</template>
        </el-table-column>
        <el-table-column label="库存上限" width="88" align="right">
          <template #default="{ row }">{{ qty(row.safetyStockUpper) }}</template>
        </el-table-column>
        <el-table-column label="预警类型" width="88" align="center">
          <template #default="{ row }">
            <el-tag :type="alertTypeMap[row.alertType]?.type ?? 'info'" size="small">
              {{ alertTypeMap[row.alertType]?.label ?? row.alertType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="gapQty" label="缺口量" width="90" align="right" sortable="custom">
          <template #default="{ row }">
            <span class="qty gap">{{ qty(row.gapQty) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100]"
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

  .card-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .tabs {
    display: flex;
    gap: 8px;
    margin-bottom: 12px;

    .tab {
      border: 1px solid #dcdfe6;
      background: #fff;
      border-radius: 4px;
      padding: 6px 14px;
      font-size: 13px;
      color: #606266;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 6px;

      b {
        color: #303133;
      }

      &:hover {
        border-color: #409eff;
        color: #409eff;
      }

      &.active {
        border-color: #409eff;
        color: #409eff;
        background: #ecf5ff;
      }

      .dot {
        width: 8px;
        height: 8px;
        border-radius: 50%;

        &.out {
          background: #f56c6c;
        }

        &.warn {
          background: #e6a23c;
        }

        &.over {
          background: #909399;
        }
      }
    }
  }

  .goods-name {
    font-weight: 500;
  }

  .sub {
    font-size: 12px;
    color: #909399;
  }

  .qty {
    font-variant-numeric: tabular-nums;

    &.out {
      color: #f56c6c;
      font-weight: 600;
    }

    &.gap {
      font-weight: 600;
    }
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }
}
</style>

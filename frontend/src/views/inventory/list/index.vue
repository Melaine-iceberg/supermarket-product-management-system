<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { getCategoryTree, type CategoryNode } from '@/api/goods'
import {
  pageInventory,
  updateSafetyStock,
  updateSafetyStockByCategory,
  type InventoryItemVO,
} from '@/api/inventory'

const auth = useAuthStore()

const stockStatusMap: Record<string, { label: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
  NORMAL: { label: '正常', type: 'success' },
  WARNING: { label: '预警', type: 'warning' },
  OUT: { label: '缺货', type: 'danger' },
  OVERSTOCK: { label: '积压', type: 'info' },
}

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2)
}

function qty(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toString()
}

// ---------- 查询（FR-INV-01：分类含子树/名称/编码/库存状态） ----------
const loading = ref(false)
const list = ref<InventoryItemVO[]>([])
const total = ref(0)
const totalCostAmount = ref(0)
const query = reactive({
  name: '',
  goodsCode: '',
  categoryId: '' as number | '',
  stockStatus: '' as string,
  page: 1,
  size: 20,
})

/** 排序白名单（与后端 InventoryMapper 一致） */
const SORTABLE = new Set(['goodsCode', 'name', 'quantity', 'costAmount', 'lastChangeTime'])
const sort = reactive<{ by?: string; dir?: 'asc' | 'desc' }>({})

const tableRef = ref()

async function load() {
  loading.value = true
  try {
    const data = await pageInventory({
      name: query.name.trim() || undefined,
      goodsCode: query.goodsCode.trim() || undefined,
      categoryId: query.categoryId === '' ? undefined : query.categoryId,
      stockStatus: query.stockStatus || undefined,
      page: query.page,
      size: query.size,
      sortBy: sort.by,
      sortDir: sort.dir,
    })
    list.value = data.list
    total.value = data.total
    totalCostAmount.value = data.totalCostAmount
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

function resetQuery() {
  Object.assign(query, { name: '', goodsCode: '', categoryId: '', stockStatus: '', page: 1 })
  sort.by = undefined
  sort.dir = undefined
  tableRef.value?.clearSort()
  load()
}

function onSortChange({ prop, order }: { prop: string | null; order: string | null }) {
  if (!order || !prop || !SORTABLE.has(prop)) {
    sort.by = undefined
    sort.dir = undefined
  } else {
    sort.by = prop
    sort.dir = order === 'ascending' ? 'asc' : 'desc'
  }
  query.page = 1
  load()
}

const categoryTree = ref<CategoryNode[]>([])

onMounted(() => {
  load()
  getCategoryTree().then((data) => (categoryTree.value = data))
})

// ---------- 安全库存阈值维护（FR-INV-06） ----------
/** 单商品阈值 */
const itemDialog = reactive({ visible: false, saving: false, goods: null as InventoryItemVO | null })
const itemForm = reactive({ lower: null as number | null, upper: null as number | null })

function openItemDialog(row: InventoryItemVO) {
  itemDialog.goods = row
  itemForm.lower = row.safetyStockLower
  itemForm.upper = row.safetyStockUpper
  itemDialog.visible = true
}

async function saveItemThreshold() {
  if (itemForm.lower != null && itemForm.upper != null && itemForm.upper < itemForm.lower) {
    ElMessage.warning('库存上限不能低于安全下限')
    return
  }
  itemDialog.saving = true
  try {
    const changed = await updateSafetyStock([
      {
        goodsId: itemDialog.goods!.goodsId,
        safetyStockLower: itemForm.lower ?? undefined,
        safetyStockUpper: itemForm.upper,
      },
    ])
    ElMessage.success(changed > 0 ? '阈值已更新' : '阈值无变化')
    itemDialog.visible = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    itemDialog.saving = false
  }
}

/** 按分类批量设置默认阈值 */
const catDialog = reactive({ visible: false, saving: false })
const catFormRef = ref<FormInstance>()
const catForm = reactive({ categoryId: '' as number | '', lower: null as number | null, upper: null as number | null })

const catRules = computed(() => ({
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  upper: [
    {
      validator: (_r: unknown, v: number | null, cb: (e?: Error) => void) => {
        if (v != null && catForm.lower != null && v < catForm.lower) {
          cb(new Error('库存上限不能低于安全下限'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
}))

async function saveCategoryThreshold() {
  await catFormRef.value?.validate()
  catDialog.saving = true
  try {
    const changed = await updateSafetyStockByCategory({
      categoryId: catForm.categoryId as number,
      safetyStockLower: catForm.lower ?? undefined,
      safetyStockUpper: catForm.upper,
    })
    ElMessage.success(`已按分类设置阈值，实际变更 ${changed} 个商品`)
    catDialog.visible = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    catDialog.saving = false
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
        <el-form-item label="库存状态">
          <el-select v-model="query.stockStatus" clearable placeholder="全部" style="width: 100px">
            <el-option label="正常" value="NORMAL" />
            <el-option label="预警" value="WARNING" />
            <el-option label="缺货" value="OUT" />
            <el-option label="积压" value="OVERSTOCK" />
          </el-select>
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
          <span>
            实时库存
            <span class="total-cost">当前筛选库存总成本：<b>{{ money(totalCostAmount) }}</b> 元</span>
          </span>
          <el-button
            v-if="auth.hasPerm('sys:inv:threshold')"
            type="primary"
            plain
            @click="catDialog.visible = true"
          >
            <el-icon><Setting /></el-icon>按分类设置阈值
          </el-button>
        </div>
      </template>

      <el-table ref="tableRef" v-loading="loading" :data="list" stripe @sort-change="onSortChange">
        <el-table-column prop="goodsCode" label="商品编码" width="130" sortable="custom" />
        <el-table-column prop="name" label="商品名称" min-width="170" sortable="custom">
          <template #default="{ row }">
            <div class="goods-name">{{ row.name }}</div>
            <div v-if="row.spec" class="goods-spec">{{ row.spec }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="categoryName" label="分类" width="110" />
        <el-table-column prop="unitName" label="单位" width="60" align="center" />
        <el-table-column prop="quantity" label="结存数量" width="110" align="right" sortable="custom">
          <template #default="{ row }">
            <span class="qty" :class="{ 'qty-negative': Number(row.quantity) < 0 }">{{ qty(row.quantity) }}</span>
            <el-tag
              v-if="row.stockStatus !== 'NORMAL'"
              :type="stockStatusMap[row.stockStatus]?.type ?? 'info'"
              size="small"
              class="stock-tag"
            >
              {{ stockStatusMap[row.stockStatus]?.label ?? row.stockStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="成本单价" width="92" align="right">
          <template #default="{ row }">{{ money(row.costPrice) }}</template>
        </el-table-column>
        <el-table-column prop="costAmount" label="库存成本" width="100" align="right" sortable="custom">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </el-table-column>
        <el-table-column label="零售价" width="86" align="right">
          <template #default="{ row }">{{ money(row.retailPrice) }}</template>
        </el-table-column>
        <el-table-column label="安全下限" width="88" align="right">
          <template #default="{ row }">{{ qty(row.safetyStockLower) }}</template>
        </el-table-column>
        <el-table-column label="库存上限" width="88" align="right">
          <template #default="{ row }">{{ qty(row.safetyStockUpper) }}</template>
        </el-table-column>
        <el-table-column prop="lastChangeTime" label="最近变动" width="165" sortable="custom">
          <template #default="{ row }">{{ fmtTime(row.lastChangeTime) }}</template>
        </el-table-column>
        <el-table-column v-if="auth.hasPerm('sys:inv:threshold')" label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openItemDialog(row as InventoryItemVO)">阈值</el-button>
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

    <!-- 单商品阈值维护（FR-INV-06） -->
    <el-dialog
      v-model="itemDialog.visible"
      :title="`安全库存阈值：${itemDialog.goods?.name ?? ''}`"
      width="420px"
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="安全下限">
          <el-input-number
            v-model="itemForm.lower"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="低于则预警/缺货"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="库存上限">
          <el-input-number
            v-model="itemForm.upper"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="高于则积压，留空不设"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="itemDialog.saving" @click="saveItemThreshold">保存</el-button>
      </template>
    </el-dialog>

    <!-- 按分类批量设置默认阈值（FR-INV-06） -->
    <el-dialog v-model="catDialog.visible" title="按分类设置默认阈值" width="440px" :close-on-click-modal="false">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="将作用于所选分类（含子分类）下的全部商品，已保存的阈值会被覆盖"
        class="dialog-alert"
      />
      <el-form ref="catFormRef" :model="catForm" :rules="catRules" label-width="90px">
        <el-form-item label="分类" prop="categoryId">
          <el-tree-select
            v-model="catForm.categoryId"
            :data="categoryTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            check-strictly
            clearable
            default-expand-all
            placeholder="选择分类（含子分类）"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="安全下限">
          <el-input-number
            v-model="catForm.lower"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="低于则预警/缺货"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="库存上限" prop="upper">
          <el-input-number
            v-model="catForm.upper"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="高于则积压，留空不设"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="catDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="catDialog.saving" @click="saveCategoryThreshold">保存</el-button>
      </template>
    </el-dialog>
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

    .total-cost {
      margin-left: 12px;
      font-size: 13px;
      color: #606266;
      font-weight: 400;

      b {
        color: #409eff;
      }
    }
  }

  .goods-name {
    font-weight: 500;
  }

  .goods-spec {
    font-size: 12px;
    color: #909399;
  }

  .qty {
    font-variant-numeric: tabular-nums;
  }

  /* 负库存高亮（BR-INV-02 / GAP-P2-11）：欠账醒目提示 */
  .qty-negative {
    color: var(--el-color-danger);
    font-weight: 600;
  }

  .stock-tag {
    margin-left: 4px;
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }

  .dialog-alert {
    margin-bottom: 14px;
  }
}
</style>

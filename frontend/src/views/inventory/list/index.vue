<script setup lang="ts">
import { notify } from '@/utils/notify'
import { moneyText } from '@/utils/format'
import { computed, onMounted, reactive, ref } from 'vue'
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

/** Element 标签类型 → Nuxt UI 徽标颜色 */
function tagColor(type?: 'success' | 'warning' | 'danger' | 'info') {
  if (type === 'success') return 'success' as const
  if (type === 'warning') return 'warning' as const
  if (type === 'danger') return 'error' as const
  return 'neutral' as const
}

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
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
    notify.warning('库存上限不能低于安全下限')
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
    notify.success(changed > 0 ? '阈值已更新' : '阈值无变化')
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
const catFormRef = ref()
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
  try {
    await catFormRef.value?.validate()
  } catch {
    return
  }
  catDialog.saving = true
  try {
    const changed = await updateSafetyStockByCategory({
      categoryId: catForm.categoryId as number,
      safetyStockLower: catForm.lower ?? undefined,
      safetyStockUpper: catForm.upper,
    })
    notify.success(`已按分类设置阈值，实际变更 ${changed} 个商品`)
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
    <UCard class="search-card" :ui="{ body: 'pb-0.5' }">
      <AppForm inline @submit.prevent>
        <AppFormItem label="商品名称">
          <AppInput v-model="query.name" placeholder="名称模糊查询" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="商品编码">
          <AppInput v-model="query.goodsCode" placeholder="编码模糊查询" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="分类">
          <AppTreeSelect
            v-model="query.categoryId"
            :data="categoryTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            clearable
            placeholder="含子分类"
            style="width: 180px"
          />
        </AppFormItem>
        <AppFormItem label="库存状态">
          <AppSelect v-model="query.stockStatus" clearable placeholder="全部" style="width: 100px">
            <AppOption label="正常" value="NORMAL" />
            <AppOption label="预警" value="WARNING" />
            <AppOption label="缺货" value="OUT" />
            <AppOption label="积压" value="OVERSTOCK" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem>
          <UButton label="查询" @click="search" />
          <UButton color="neutral" variant="outline" label="重置" @click="resetQuery" />
        </AppFormItem>
      </AppForm>
    </UCard>

    <UCard>
      <template #header>
        <div class="card-head">
          <span>
            实时库存
            <span class="total-cost">当前筛选库存总成本：<b>{{ money(totalCostAmount) }}</b> 元</span>
          </span>
          <UButton
            v-if="auth.hasPerm('sys:inv:threshold')"
            variant="outline"
            icon="i-lucide-settings"
            label="按分类设置阈值"
            @click="catDialog.visible = true"
          />
        </div>
      </template>

      <AppTable ref="tableRef" :loading="loading" border :data="list" stripe @sort-change="onSortChange">
        <AppTableColumn prop="goodsCode" label="商品编码" width="130" sortable="custom" />
        <AppTableColumn prop="name" label="商品名称" min-width="170" sortable="custom">
          <template #default="{ row }">
            <div class="goods-name">{{ row.name }}</div>
            <div v-if="row.spec" class="goods-spec">{{ row.spec }}</div>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="categoryName" label="分类" width="110" />
        <AppTableColumn prop="unitName" label="单位" width="60" align="center" />
        <AppTableColumn prop="quantity" label="结存数量" width="110" align="right" sortable="custom">
          <template #default="{ row }">
            <span class="qty" :class="{ 'qty-negative': Number(row.quantity) < 0 }">{{ qty(row.quantity) }}</span>
            <UBadge
              v-if="row.stockStatus !== 'NORMAL'"
              :color="tagColor(stockStatusMap[row.stockStatus]?.type)"
              variant="soft"
              size="sm"
              class="stock-tag"
            >
              {{ stockStatusMap[row.stockStatus]?.label ?? row.stockStatus }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="成本单价" width="92" align="right">
          <template #default="{ row }">{{ money(row.costPrice) }}</template>
        </AppTableColumn>
        <AppTableColumn prop="costAmount" label="库存成本" width="100" align="right" sortable="custom">
          <template #default="{ row }">{{ money(row.costAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="零售价" width="86" align="right">
          <template #default="{ row }">{{ money(row.retailPrice) }}</template>
        </AppTableColumn>
        <AppTableColumn label="安全下限" width="88" align="right">
          <template #default="{ row }">{{ qty(row.safetyStockLower) }}</template>
        </AppTableColumn>
        <AppTableColumn label="库存上限" width="88" align="right">
          <template #default="{ row }">{{ qty(row.safetyStockUpper) }}</template>
        </AppTableColumn>
        <AppTableColumn prop="lastChangeTime" label="最近变动" width="165" sortable="custom">
          <template #default="{ row }">{{ fmtTime(row.lastChangeTime) }}</template>
        </AppTableColumn>
        <AppTableColumn v-if="auth.hasPerm('sys:inv:threshold')" label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <UButton variant="link" size="sm" label="阈值" @click="openItemDialog(row as InventoryItemVO)" />
          </template>
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

    <!-- 单商品阈值维护（FR-INV-06） -->
    <AppModal
      v-model="itemDialog.visible"
      :title="`安全库存阈值：${itemDialog.goods?.name ?? ''}`"
      width="420px"
      :close-on-click-modal="false"
    >
      <AppForm label-width="90px">
        <AppFormItem label="安全下限">
          <AppInputNumber
            v-model="itemForm.lower"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="低于则预警/缺货"
            style="width: 100%"
          />
        </AppFormItem>
        <AppFormItem label="库存上限">
          <AppInputNumber
            v-model="itemForm.upper"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="高于则积压，留空不设"
            style="width: 100%"
          />
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="itemDialog.visible = false" />
        <UButton :loading="itemDialog.saving" label="保存" @click="saveItemThreshold" />
      </template>
    </AppModal>

    <!-- 按分类批量设置默认阈值（FR-INV-06） -->
    <AppModal v-model="catDialog.visible" title="按分类设置默认阈值" width="440px" :close-on-click-modal="false">
      <UAlert
        color="info"
        icon="i-lucide-info"
        title="将作用于所选分类（含子分类）下的全部商品，已保存的阈值会被覆盖"
        class="dialog-alert"
      />
      <AppForm ref="catFormRef" :model="catForm" :rules="catRules" label-width="90px">
        <AppFormItem label="分类" prop="categoryId">
          <AppTreeSelect
            v-model="catForm.categoryId"
            :data="categoryTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            clearable
            placeholder="选择分类（含子分类）"
            style="width: 100%"
          />
        </AppFormItem>
        <AppFormItem label="安全下限">
          <AppInputNumber
            v-model="catForm.lower"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="低于则预警/缺货"
            style="width: 100%"
          />
        </AppFormItem>
        <AppFormItem label="库存上限" prop="upper">
          <AppInputNumber
            v-model="catForm.upper"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="高于则积压，留空不设"
            style="width: 100%"
          />
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="catDialog.visible = false" />
        <UButton :loading="catDialog.saving" label="保存" @click="saveCategoryThreshold" />
      </template>
    </AppModal>
  </div>
</template>

<style scoped lang="scss">
.page {
  .search-card {
    margin-bottom: 12px;
  }

  .card-head {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .total-cost {
      margin-left: 12px;
      font-size: 13px;
      color: var(--el-text-color-regular);
      font-weight: 400;

      b {
        color: var(--el-color-primary);
      }
    }
  }

  .goods-name {
    font-weight: 500;
  }

  .goods-spec {
    font-size: 12px;
    color: var(--el-text-color-secondary);
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

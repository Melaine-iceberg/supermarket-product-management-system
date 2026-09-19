<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { saveBlob } from '@/api/request'
import {
  getCategoryTree,
  listBrands,
  pageGoods,
  updateGoodsStatus,
  deleteGoods,
  exportGoods,
  type CategoryNode,
  type BrandVO,
  type GoodsListVO,
} from '@/api/goods'

const auth = useAuthStore()

// ---------- 查询条件（FR-GOODS-03 多维检索） ----------
const loading = ref(false)
const list = ref<GoodsListVO[]>([])
const total = ref(0)
const query = reactive({
  name: '',
  goodsCode: '',
  barcode: '',
  categoryId: '' as number | '',
  brandId: '' as number | '',
  status: '' as number | '',
  stockStatus: '' as string,
  isWeighed: '' as number | '',
  allowReturn: '' as number | '',
  page: 1,
  size: 20,
})

/** 排序白名单（与后端 GoodsMapper 一致） */
const SORTABLE = new Set(['goodsCode', 'name', 'quantity', 'retailPrice', 'createTime'])
const sort = reactive<{ by?: string; dir?: 'asc' | 'desc' }>({})

const stockStatusMap: Record<string, { label: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
  NORMAL: { label: '正常', type: 'success' },
  WARNING: { label: '预警', type: 'warning' },
  OUT: { label: '缺货', type: 'danger' },
  OVERSTOCK: { label: '积压', type: 'info' },
}

async function load() {
  loading.value = true
  try {
    const data = await pageGoods({
      name: query.name.trim() || undefined,
      goodsCode: query.goodsCode.trim() || undefined,
      barcode: query.barcode.trim() || undefined,
      categoryId: query.categoryId === '' ? undefined : query.categoryId,
      brandId: query.brandId === '' ? undefined : query.brandId,
      status: query.status === '' ? undefined : query.status,
      stockStatus: query.stockStatus || undefined,
      isWeighed: query.isWeighed === '' ? undefined : query.isWeighed,
      allowReturn: query.allowReturn === '' ? undefined : query.allowReturn,
      page: query.page,
      size: query.size,
      sortBy: sort.by,
      sortDir: sort.dir,
    })
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

function resetQuery() {
  Object.assign(query, {
    name: '',
    goodsCode: '',
    barcode: '',
    categoryId: '',
    brandId: '',
    status: '',
    stockStatus: '',
    isWeighed: '',
    allowReturn: '',
    page: 1,
  })
  sort.by = undefined
  sort.dir = undefined
  tableRef.value?.clearSort()
  load()
}

/** 表头排序（FR-GOODS-03）：白名单字段映射 sortBy/sortDir */
const tableRef = ref()

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

// ---------- 筛选下拉数据 ----------
const categoryTree = ref<CategoryNode[]>([])
const brands = ref<BrandVO[]>([])

onMounted(() => {
  load()
  getCategoryTree().then((data) => (categoryTree.value = data))
  listBrands().then((data) => (brands.value = data))
})

// ---------- 停用 / 启用（FR-GOODS-05） ----------
async function toggleStatus(row: GoodsListVO) {
  const disabling = row.status === 1
  const tip = disabling
    ? `停用后「${row.name}」不可再销售/采购，档案将永久保留，确定停用？`
    : `确定启用商品「${row.name}」？`
  await ElMessageBox.confirm(tip, disabling ? '停用商品' : '启用商品', { type: 'warning' })
  await updateGoodsStatus(row.id, disabling ? 0 : 1)
  ElMessage.success(disabling ? '已停用' : '已启用')
  load()
}

// ---------- 删除（FR-GOODS-06，仅从未发生业务的商品可删） ----------
async function handleDelete(row: GoodsListVO) {
  await ElMessageBox.confirm(
    `删除后不可恢复，仅从未发生过业务的商品可删除，确定删除「${row.name}」？`,
    '删除商品',
    { type: 'warning' },
  )
  await deleteGoods(row.id)
  ElMessage.success('已删除')
  load()
}

// ---------- 导出（FR-GOODS-12：与当前筛选一致） ----------
const exporting = ref(false)

async function handleExport() {
  exporting.value = true
  try {
    const blob = await exportGoods({
      name: query.name.trim() || undefined,
      goodsCode: query.goodsCode.trim() || undefined,
      barcode: query.barcode.trim() || undefined,
      categoryId: query.categoryId === '' ? undefined : query.categoryId,
      brandId: query.brandId === '' ? undefined : query.brandId,
      status: query.status === '' ? undefined : query.status,
      stockStatus: query.stockStatus || undefined,
      isWeighed: query.isWeighed === '' ? undefined : query.isWeighed,
      allowReturn: query.allowReturn === '' ? undefined : query.allowReturn,
      sortBy: sort.by,
      sortDir: sort.dir,
    })
    const ts = new Date().toISOString().slice(0, 19).replace(/[-T:]/g, '')
    saveBlob(blob, `商品清单_${ts}.xlsx`)
    ElMessage.success('导出成功')
  } finally {
    exporting.value = false
  }
}

function money(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2)
}
</script>

<template>
  <div class="page">
    <!-- 多维检索（FR-GOODS-03） -->
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="商品名称">
          <el-input v-model="query.name" placeholder="名称模糊查询" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="商品编码">
          <el-input v-model="query.goodsCode" placeholder="编码模糊查询" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="条码">
          <el-input v-model="query.barcode" placeholder="条码模糊查询" clearable @keyup.enter="search" />
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
        <el-form-item label="品牌">
          <el-select v-model="query.brandId" clearable placeholder="全部" style="width: 140px">
            <el-option
              v-for="b in brands"
              :key="b.id"
              :label="b.status === 1 ? b.name : `${b.name}（停用）`"
              :value="b.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 100px">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="库存状态">
          <el-select v-model="query.stockStatus" clearable placeholder="全部" style="width: 100px">
            <el-option label="正常" value="NORMAL" />
            <el-option label="预警" value="WARNING" />
            <el-option label="缺货" value="OUT" />
            <el-option label="积压" value="OVERSTOCK" />
          </el-select>
        </el-form-item>
        <el-form-item label="称重商品">
          <el-select v-model="query.isWeighed" clearable placeholder="全部" style="width: 100px">
            <el-option label="是" :value="1" />
            <el-option label="否" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="允许退货">
          <el-select v-model="query.allowReturn" clearable placeholder="全部" style="width: 100px">
            <el-option label="允许" :value="1" />
            <el-option label="不允许" :value="0" />
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
          <span>商品列表</span>
          <el-button
            v-if="auth.hasPerm('sys:goods:export')"
            :loading="exporting"
            @click="handleExport"
          >
            <el-icon><Download /></el-icon>导出清单
          </el-button>
        </div>
      </template>

      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="list"
        stripe
        @sort-change="onSortChange"
      >
        <el-table-column prop="goodsCode" label="商品编码" width="130" sortable="custom" />
        <el-table-column prop="barcode" label="条码" width="150">
          <template #default="{ row }">{{ row.barcode || '—' }}</template>
        </el-table-column>
        <el-table-column prop="name" label="商品名称" min-width="170" sortable="custom">
          <template #default="{ row }">
            <div class="goods-name">{{ row.name }}</div>
            <div v-if="row.spec" class="goods-spec">{{ row.spec }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="categoryName" label="分类" width="110" />
        <el-table-column label="品牌" width="100">
          <template #default="{ row }">{{ row.brandName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="unitName" label="单位" width="64" />
        <el-table-column label="进货价" width="90" align="right">
          <template #default="{ row }">{{ money(row.purchasePrice) }}</template>
        </el-table-column>
        <el-table-column prop="retailPrice" label="零售价" width="90" align="right" sortable="custom">
          <template #default="{ row }">{{ money(row.retailPrice) }}</template>
        </el-table-column>
        <el-table-column label="会员价" width="90" align="right">
          <template #default="{ row }">{{ money(row.memberPrice) }}</template>
        </el-table-column>
        <el-table-column label="促销价" width="100" align="right">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.promoPrice != null"
              :content="`促销截止：${row.promoEndTime}`"
              placement="top"
            >
              <el-tag type="danger" size="small" class="promo-tag">
                {{ money(row.promoPrice) }}
              </el-tag>
            </el-tooltip>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="quantity" label="库存" width="110" align="right" sortable="custom">
          <template #default="{ row }">
            <span class="qty">{{ row.quantity }} {{ row.unitName }}</span>
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
        <el-table-column label="称重" width="64" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.isWeighed === 1" size="small" effect="plain">称重</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="72" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" sortable="custom" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasPerm('sys:goods:status')"
              link
              :type="row.status === 1 ? 'danger' : 'success'"
              @click="toggleStatus(row as GoodsListVO)"
            >{{ row.status === 1 ? '停用' : '启用' }}</el-button>
            <el-button
              v-if="auth.hasPerm('sys:goods:delete')"
              link
              type="danger"
              @click="handleDelete(row as GoodsListVO)"
            >删除</el-button>
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

  .goods-name {
    font-weight: 500;
  }

  .goods-spec {
    font-size: 12px;
    color: #909399;
  }

  .promo-tag {
    font-weight: 600;
  }

  .qty {
    font-variant-numeric: tabular-nums;
  }

  .stock-tag {
    margin-left: 4px;
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }
}
</style>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { saveBlob } from '@/api/request'
import {
  getCategoryTree,
  listBrands,
  listUnits,
  pageGoods,
  updateGoodsStatus,
  deleteGoods,
  exportGoods,
  getGoodsDetail,
  createGoods,
  updateGoods,
  nextBarcode,
  type CategoryNode,
  type BrandVO,
  type UnitVO,
  type GoodsListVO,
  type GoodsCreatedVO,
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
const units = ref<UnitVO[]>([])

onMounted(() => {
  load()
  getCategoryTree().then((data) => (categoryTree.value = data))
  listBrands().then((data) => (brands.value = data))
  listUnits().then((data) => (units.value = data))
})

// ---------- 新增 / 编辑表单（FR-GOODS-01/02） ----------
const dialogVisible = ref(false)
const editing = ref<GoodsListVO | null>(null)
const editingDetail = ref<{ barcode: string; prices: number[]; image: string | null } | null>(null)
const formRef = ref<FormInstance>()
const saving = ref(false)
const detailLoading = ref(false)

const form = reactive({
  name: '',
  categoryId: '' as number | '',
  unitId: '' as number | '',
  brandId: '' as number | '',
  spec: '',
  origin: '',
  purchasePrice: null as number | null,
  retailPrice: null as number | null,
  memberPrice: null as number | null,
  goodsCode: '',
  barcode: '',
  pluCode: '',
  isWeighed: 0,
  allowReturn: 1,
  safetyStockLower: null as number | null,
  safetyStockUpper: null as number | null,
  initialStock: null as number | null,
  remark: '',
  priceChangeReason: '',
})

/** 表单用分类树：仅启用的叶子分类可选（BR-CAT-04），其余置灰 */
interface CategoryOption {
  id: number
  name: string
  disabled: boolean
  children?: CategoryOption[]
}

function toCategoryOptions(nodes: CategoryNode[]): CategoryOption[] {
  return nodes
    .slice()
    .sort((a, b) => a.sortNo - b.sortNo)
    .map((n) => ({
      id: n.id,
      name: n.name,
      disabled: !(n.leaf && n.status === 1),
      children: n.children.length ? toCategoryOptions(n.children) : undefined,
    }))
}

const categoryOptions = computed(() => toCategoryOptions(categoryTree.value))

/** 单位下拉：称重商品只能选重量单位（BR-GOODS-05），非称重不限 */
const unitOptions = computed(() =>
  units.value.filter((u) => u.status === 1 && (form.isWeighed === 1 ? u.isWeight === 1 : true)),
)

// 称重切换后，当前单位若不再可选则清空
watch(
  () => form.isWeighed,
  () => {
    if (form.unitId !== '' && !unitOptions.value.some((u) => u.id === form.unitId)) {
      form.unitId = ''
    }
  },
)

/** 编辑时是否发生价格变更（决定是否展示"调价原因"，FR-GOODS-10） */
const priceChanged = computed(() => {
  if (!editingDetail.value) return false
  const [purchase, retail, member] = editingDetail.value.prices
  return (
    (form.purchasePrice ?? -1) !== purchase ||
    (form.retailPrice ?? -1) !== retail ||
    (form.memberPrice ?? -1) !== member
  )
})

const rules = computed(() => ({
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择所属分类', trigger: 'change' }],
  unitId: [{ required: true, message: '请选择计量单位', trigger: 'change' }],
  retailPrice: [{ required: true, message: '请输入零售单价', trigger: 'blur' }],
  memberPrice: [
    {
      validator: (_r: unknown, v: number | null, cb: (e?: Error) => void) => {
        if (v != null && form.retailPrice != null && v > form.retailPrice) {
          cb(new Error('会员价不能高于零售单价'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
  safetyStockUpper: [
    {
      validator: (_r: unknown, v: number | null, cb: (e?: Error) => void) => {
        if (v != null && form.safetyStockLower != null && v < form.safetyStockLower) {
          cb(new Error('库存上限不能低于安全库存下限'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
  pluCode: [
    { required: form.isWeighed === 1, message: '称重商品必须填写 PLU 码', trigger: 'blur' },
  ],
  barcode: [
    ...(editing.value
      ? [{ required: true, message: '请输入商品条码', trigger: 'blur' }]
      : []),
    { pattern: /^\d{1,20}$/, message: '条码须为 1~20 位数字', trigger: 'blur' },
  ],
}))

function resetForm() {
  editing.value = null
  editingDetail.value = null
  formRef.value?.resetFields()
  Object.assign(form, {
    name: '',
    categoryId: '',
    unitId: '',
    brandId: '',
    spec: '',
    origin: '',
    purchasePrice: null,
    retailPrice: null,
    memberPrice: null,
    goodsCode: '',
    barcode: '',
    pluCode: '',
    isWeighed: 0,
    allowReturn: 1,
    safetyStockLower: null,
    safetyStockUpper: null,
    initialStock: null,
    remark: '',
    priceChangeReason: '',
  })
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

/** 编辑前先拉详情：列表行缺 remark/image，且编辑是全量覆盖（FR-GOODS-02） */
async function openEdit(row: GoodsListVO) {
  resetForm()
  dialogVisible.value = true
  detailLoading.value = true
  try {
    const detail = await getGoodsDetail(row.id)
    const g = detail.goods
    editing.value = row
    editingDetail.value = {
      barcode: g.barcode ?? '',
      prices: [g.purchasePrice, g.retailPrice, g.memberPrice],
      image: g.image,
    }
    Object.assign(form, {
      name: g.name,
      categoryId: g.categoryId,
      unitId: g.unitId,
      brandId: g.brandId ?? '',
      spec: g.spec ?? '',
      origin: g.origin ?? '',
      purchasePrice: g.purchasePrice,
      retailPrice: g.retailPrice,
      memberPrice: g.memberPrice,
      goodsCode: g.goodsCode,
      barcode: g.barcode ?? '',
      pluCode: g.pluCode ?? '',
      isWeighed: g.isWeighed,
      allowReturn: g.allowReturn,
      safetyStockLower: g.safetyStockLower,
      safetyStockUpper: g.safetyStockUpper,
      remark: g.remark ?? '',
    })
    dialogVisible.value = true
  } finally {
    detailLoading.value = false
  }
}

/** 店内码预览（FR-GOODS-07）：不落库，仅填充输入框 */
async function fillInStoreBarcode() {
  const { barcode } = await nextBarcode()
  form.barcode = barcode
  ElMessage.info(`已生成店内码预览：${barcode}`)
}

function num(v: number | null | undefined) {
  return v == null ? undefined : v
}

async function save() {
  await formRef.value?.validate()
  const base = {
    name: form.name.trim(),
    categoryId: form.categoryId as number,
    unitId: form.unitId as number,
    brandId: form.brandId === '' ? undefined : (form.brandId as number),
    spec: form.spec.trim() || undefined,
    origin: form.origin.trim() || undefined,
    purchasePrice: num(form.purchasePrice),
    retailPrice: form.retailPrice as number,
    memberPrice: num(form.memberPrice),
    barcode: form.barcode.trim() || undefined,
    pluCode: form.pluCode.trim() || undefined,
    isWeighed: form.isWeighed,
    allowReturn: form.allowReturn,
    safetyStockLower: num(form.safetyStockLower),
    safetyStockUpper: num(form.safetyStockUpper),
    remark: form.remark.trim() || undefined,
  }
  saving.value = true
  try {
    if (editing.value) {
      // 条码变更二次确认（FR-GOODS-02）
      const newBarcode = form.barcode.trim()
      let confirmed = false
      if (newBarcode !== editingDetail.value?.barcode) {
        await ElMessageBox.confirm(
          '条码是收银扫码的关键标识，修改后旧条码将立即失效，确定修改？',
          '条码变更确认',
          { type: 'warning' },
        )
        confirmed = true
      }
      await updateGoods(editing.value.id, {
        ...base,
        barcode: newBarcode,
        barcodeConfirmed: confirmed,
        image: editingDetail.value?.image ?? undefined,
        priceChangeReason: priceChanged.value ? form.priceChangeReason.trim() || undefined : undefined,
      })
      ElMessage.success('商品已更新')
    } else {
      const created = await createGoods({
        ...base,
        goodsCode: form.goodsCode.trim() || undefined,
        initialStock: num(form.initialStock),
      })
      showCreated(created)
    }
    dialogVisible.value = false
    load()
  } catch {
    // 拦截器已提示；确认框取消则停留表单
  } finally {
    saving.value = false
  }
}

// 新增成功后回显系统生成的编码/店内码（FR-GOODS-01/07）
const createdDialog = reactive<{ visible: boolean; data: GoodsCreatedVO | null }>({
  visible: false,
  data: null,
})

function showCreated(data: GoodsCreatedVO) {
  createdDialog.data = data
  createdDialog.visible = true
}

async function copyText(text: string) {
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制到剪贴板')
}

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
          <div>
            <el-button
              v-if="auth.hasPerm('sys:goods:create')"
              type="primary"
              @click="openCreate"
            >
              <el-icon><Plus /></el-icon>新增商品
            </el-button>
            <el-button
              v-if="auth.hasPerm('sys:goods:export')"
              :loading="exporting"
              @click="handleExport"
            >
              <el-icon><Download /></el-icon>导出清单
            </el-button>
          </div>
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
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasPerm('sys:goods:update')"
              link
              type="primary"
              @click="openEdit(row as GoodsListVO)"
            >编辑</el-button>
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

    <!-- 新增 / 编辑对话框（FR-GOODS-01/02） -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? `编辑商品：${editing.name}` : '新增商品'"
      width="720px"
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="!editing"
        type="info"
        :closable="false"
        show-icon
        title="商品编码/条码留空时由系统自动生成；初始库存 >0 将生成「期初建账」库存流水"
        class="dialog-alert"
      />
      <el-form ref="formRef" v-loading="detailLoading" :model="form" :rules="rules" label-width="96px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="商品名称" prop="name">
              <el-input v-model="form.name" maxlength="100" placeholder="1~100 个字符" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="所属分类" prop="categoryId">
              <el-tree-select
                v-model="form.categoryId"
                :data="categoryOptions"
                :props="{ label: 'name', children: 'children', disabled: 'disabled' }"
                node-key="id"
                check-strictly
                default-expand-all
                :render-after-expand="false"
                placeholder="仅启用的叶子分类可选"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="计量单位" prop="unitId">
              <el-select v-model="form.unitId" placeholder="必选" style="width: 100%">
                <el-option
                  v-for="u in unitOptions"
                  :key="u.id"
                  :label="`${u.name}（${u.symbol ?? u.name}）`"
                  :value="u.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="品牌">
              <el-select v-model="form.brandId" clearable placeholder="选填" style="width: 100%">
                <el-option
                  v-for="b in brands.filter((b) => b.status === 1)"
                  :key="b.id"
                  :label="b.name"
                  :value="b.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="称重商品">
              <el-radio-group v-model="form.isWeighed">
                <el-radio :value="1">是</el-radio>
                <el-radio :value="0">否</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col v-if="form.isWeighed === 1" :span="8">
            <el-form-item label="PLU 码" prop="pluCode">
              <el-input v-model="form.pluCode" maxlength="10" placeholder="称重商品必填" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="规格">
              <el-input v-model="form.spec" maxlength="50" placeholder="如 330ml" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="产地">
              <el-input v-model="form.origin" maxlength="100" placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="进货参考价">
              <el-input-number
                v-model="form.purchasePrice"
                :min="0"
                :precision="2"
                :controls="false"
                placeholder="≥0"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="零售单价" prop="retailPrice">
              <el-input-number
                v-model="form.retailPrice"
                :min="0.01"
                :precision="2"
                :controls="false"
                placeholder=">0"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="会员价" prop="memberPrice">
              <el-input-number
                v-model="form.memberPrice"
                :min="0"
                :precision="2"
                :controls="false"
                placeholder="≤零售价"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="安全下限">
              <el-input-number
                v-model="form.safetyStockLower"
                :min="0"
                :precision="2"
                :controls="false"
                placeholder="≥0"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="库存上限" prop="safetyStockUpper">
              <el-input-number
                v-model="form.safetyStockUpper"
                :min="0"
                :precision="2"
                :controls="false"
                placeholder="积压预警"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col v-if="!editing" :span="8">
            <el-form-item label="初始库存">
              <el-input-number
                v-model="form.initialStock"
                :min="0"
                :precision="2"
                :controls="false"
                placeholder="留空为 0"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="商品编码">
              <el-input
                v-model="form.goodsCode"
                :disabled="!!editing"
                maxlength="20"
                :placeholder="editing ? '创建后不可修改' : '留空自动生成（分类码+流水）'"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="商品条码" prop="barcode">
              <el-input v-model="form.barcode" maxlength="20" placeholder="1~20 位数字">
                <template v-if="!editing" #append>
                  <el-button @click="fillInStoreBarcode">店内码</el-button>
                </template>
              </el-input>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="允许退货">
              <el-radio-group v-model="form.allowReturn">
                <el-radio :value="1">允许</el-radio>
                <el-radio :value="0">不允许</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col v-if="editing && priceChanged" :span="16">
            <el-form-item label="调价原因">
              <el-input
                v-model="form.priceChangeReason"
                maxlength="200"
                placeholder="将随调价历史留痕（FR-GOODS-10），选填"
              />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="备注">
              <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="detailLoading" @click="save">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 新增成功：回显生成的编码/店内码 -->
    <el-dialog v-model="createdDialog.visible" title="商品创建成功" width="440px">
      <el-alert
        type="success"
        :closable="false"
        show-icon
        :title="`「${form.name}」已创建`"
        class="dialog-alert"
      />
      <div class="created-box">
        <div class="created-row">
          <span class="created-label">商品编码</span>
          <span class="created-value">{{ createdDialog.data?.goodsCode }}</span>
          <el-button link type="primary" @click="copyText(createdDialog.data?.goodsCode ?? '')">
            复制
          </el-button>
        </div>
        <div class="created-row">
          <span class="created-label">商品条码</span>
          <span class="created-value">{{ createdDialog.data?.barcode }}</span>
          <el-button link type="primary" @click="copyText(createdDialog.data?.barcode ?? '')">
            复制
          </el-button>
        </div>
      </div>
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

  .dialog-alert {
    margin-bottom: 14px;
  }

  .created-box {
    .created-row {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 10px 14px;
      background: #f5f7fa;
      border-radius: 6px;
      margin-bottom: 8px;

      .created-label {
        color: #909399;
        font-size: 13px;
        flex: none;
      }

      .created-value {
        font-family: monospace;
        font-size: 17px;
        letter-spacing: 1px;
        flex: 1;
      }
    }
  }
}
</style>

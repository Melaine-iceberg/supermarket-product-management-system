<script setup lang="ts">
import { notify } from '@/utils/notify'
import { moneyText } from '@/utils/format'
import { onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  generateSuggestOrders,
  pageSuggestPurchase,
  type SuggestItemVO,
} from '@/api/purchase'
import { getCategoryTree, type CategoryNode } from '@/api/goods'
import { pageSuppliers, type SupplierListVO } from '@/api/supplier'

const auth = useAuthStore()

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
}

// ==================== 建议采购清单（FR-PUR-02） ====================
const loading = ref(false)
const list = ref<SuggestItemVO[]>([])
const total = ref(0)
const categoryTree = ref<CategoryNode[]>([])
const suppliers = ref<SupplierListVO[]>([])
const query = reactive({
  name: '',
  goodsCode: '',
  categoryId: null as number | null,
  alertType: '' as string,
  page: 1,
  size: 20,
})

/** 行可选条件：建议量 > 0（无默认供应商也可勾选，生成时须在弹窗中指定供应商，UC-INV-002 6a） */
function selectable(row: SuggestItemVO) {
  return row.suggestQty > 0
}

async function load() {
  loading.value = true
  try {
    const data = await pageSuggestPurchase({
      name: query.name.trim() || undefined,
      goodsCode: query.goodsCode.trim() || undefined,
      categoryId: query.categoryId ?? undefined,
      alertType: query.alertType || undefined,
      page: query.page,
      size: query.size,
    })
    list.value = data.list
    total.value = data.total
  } catch {
    // 请求失败时清空列表并保留错误提示（由 request 拦截器弹出），避免误显示为“暂无数据”
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

function resetQuery() {
  Object.assign(query, { name: '', goodsCode: '', categoryId: null, alertType: '', page: 1 })
  load()
}

// ==================== 一键生成草稿（FR-PUR-02） ====================
interface GenerateItem {
  goodsId: number
  goodsName: string
  unitName: string
  suggestQty: number
  quantity: number | null
  price: number | null
  /** 供应商（可改，UC-INV-002 6a）：默认取建议的默认供应商，未维护则须手动指定 */
  supplierId: number | null
  supplierName: string
}

const selection = ref<SuggestItemVO[]>([])
const generateDialog = reactive({
  visible: false,
  saving: false,
  items: [] as GenerateItem[],
})
const resultDialog = reactive({
  visible: false,
  orders: [] as { orderId: number; orderNo: string; supplierName: string; itemCount: number; totalAmount: number }[],
  skipped: [] as { goodsId: number; goodsName: string; reason: string }[],
})

function onSelectionChange(rows: SuggestItemVO[]) {
  selection.value = rows
}

function openGenerate() {
  if (selection.value.length === 0) {
    notify.warning('请勾选需要采购的商品')
    return
  }
  generateDialog.items = selection.value.map((row) => ({
    goodsId: row.goodsId,
    goodsName: `${row.goodsName}${row.spec ? '（' + row.spec + '）' : ''}`,
    unitName: row.unitName ?? '',
    suggestQty: row.suggestQty,
    quantity: row.suggestQty,
    // 单价默认链：最近采购价 → 供货价（BR-INV-07 建议展示）
    price: row.lastPurchasePrice ?? row.supplyPrice ?? 0,
    supplierId: row.supplierId,
    supplierName: row.supplierName ?? '',
  }))
  generateDialog.visible = true
}

async function submitGenerate() {
  if (generateDialog.items.some((i) => i.quantity == null || i.quantity <= 0)) {
    notify.warning('采购数量必须大于 0')
    return
  }
  if (generateDialog.items.some((i) => i.supplierId == null)) {
    notify.warning('存在未指定供应商的行，请在弹窗中为每行选择供应商（FR-SUP-04）')
    return
  }
  generateDialog.saving = true
  try {
    const result = await generateSuggestOrders(
      generateDialog.items.map((i) => ({
        goodsId: i.goodsId,
        quantity: Number(i.quantity),
        price: i.price != null ? Number(i.price) : undefined,
        supplierId: i.supplierId ?? undefined,
      })),
    )
    generateDialog.visible = false
    resultDialog.orders = result.orders
    resultDialog.skipped = result.skippedGoods
    resultDialog.visible = true
    load()
  } finally {
    generateDialog.saving = false
  }
}

onMounted(() => {
  load()
  getCategoryTree().then((tree) => (categoryTree.value = tree))
  // 供应商下拉（生成草稿时可改供应商，UC-INV-002 6a）
  pageSuppliers({ status: 1, page: 1, size: 200 }).then((data) => (suppliers.value = data.list))
})
</script>

<template>
  <div class="page">
    <UCard>
      <template #header>
        <div class="card-header">
          <span>建议采购量 = ceil(安全库存 × K + 日均销量 × 采购提前期 − 当前结存 − 在途采购量)，K/统计天数/提前期取系统参数（BR-INV-07）</span>
        </div>
      </template>

      <AppForm inline @submit.prevent="search">
        <AppFormItem label="商品">
          <AppInput v-model="query.name" placeholder="商品名称" clearable style="width: 150px" @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="编码">
          <AppInput v-model="query.goodsCode" placeholder="商品编码" clearable style="width: 130px" @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="分类">
          <AppTreeSelect
            v-model="query.categoryId"
            :data="categoryTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            clearable
            placeholder="含子分类"
            style="width: 170px"
          />
        </AppFormItem>
        <AppFormItem label="类型">
          <AppSelect v-model="query.alertType" placeholder="全部" clearable style="width: 110px">
            <AppOption label="缺货" value="OUT" />
            <AppOption label="预警" value="WARNING" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem>
          <div class="flex flex-wrap gap-2">
            <UButton label="查询" @click="search" />
            <UButton color="neutral" variant="outline" label="重置" @click="resetQuery" />
            <UButton
              v-if="auth.hasPerm('sys:pur:create')"
              color="success"
              :disabled="selection.length === 0"
              :label="`生成草稿采购单（已选 ${selection.length}）`"
              @click="openGenerate"
            />
          </div>
        </AppFormItem>
      </AppForm>

      <AppTable
        :loading="loading"
        border
        :data="list"
        stripe
        @selection-change="onSelectionChange"
      >
        <AppTableColumn type="selection" width="45" :selectable="selectable" />
        <AppTableColumn prop="goodsCode" label="编码" width="100" fixed="left" />
        <AppTableColumn prop="goodsName" label="商品" min-width="140" show-overflow-tooltip fixed="left" />
        <AppTableColumn label="类型" width="75" align="center">
          <template #default="{ row }">
            <UBadge :color="row.suggestType === 'OUT' ? 'error' : 'warning'" variant="soft" size="sm">
              {{ row.suggestType === 'OUT' ? '缺货' : '预警' }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="categoryName" label="分类" width="95" show-overflow-tooltip />
        <AppTableColumn label="结存" width="75" align="right" prop="quantity" />
        <AppTableColumn label="安全下限" width="85" align="right" prop="safetyStockLower" />
        <AppTableColumn label="日均销量" width="85" align="right">
          <template #default="{ row }">{{ row.avgDailySales }}</template>
        </AppTableColumn>
        <AppTableColumn label="在途" width="70" align="right" prop="onWayQty" />
        <AppTableColumn label="提前期" width="70" align="center">
          <template #default="{ row }">{{ row.leadTimeDays }}天</template>
        </AppTableColumn>
        <AppTableColumn label="建议采购量" width="100" align="right">
          <template #default="{ row }">
            <span :class="{ 'qty-zero': row.suggestQty <= 0 }">{{ row.suggestQty }}</span>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="unitName" label="单位" width="55" />
        <AppTableColumn label="默认供应商" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.supplierName">{{ row.supplierName }}</span>
            <UBadge v-else color="warning" variant="soft" size="sm">待指定供应商</UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="供货价" width="80" align="right">
          <template #default="{ row }">{{ money(row.supplyPrice) }}</template>
        </AppTableColumn>
        <AppTableColumn label="最近采购价" width="95" align="right">
          <template #default="{ row }">{{ money(row.lastPurchasePrice) }}</template>
        </AppTableColumn>
        <AppTableColumn label="计算依据" min-width="280">
          <template #default="{ row }">
            <span class="basis">{{ row.calcBasis }}</span>
          </template>
        </AppTableColumn>
      </AppTable>

      <div class="pagination-row">
        <AppPagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </UCard>

    <!-- ==================== 生成草稿确认（FR-PUR-02） ==================== -->
    <AppModal v-model="generateDialog.visible" title="生成草稿采购单" width="760px">
      <UAlert color="info" variant="soft" icon="i-lucide-info" title="按供应商自动分组生成草稿采购单（预计到货日期 = 今日 + 供应商提前期）；供应商可修改，未维护默认供应商的行须手动指定；数量与单价可调整，保存后可在采购单列表继续编辑" class="dialog-alert" />
      <AppTable :data="generateDialog.items" size="small" stripe max-height="360">
        <AppTableColumn prop="goodsName" label="商品" min-width="170" show-overflow-tooltip />
        <AppTableColumn label="供应商（可改）" width="170">
          <template #default="{ row }">
            <AppSelect
              v-model="row.supplierId"
              filterable
              size="small"
              placeholder="待指定供应商"
              style="width: 100%"
            >
              <AppOption v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
            </AppSelect>
          </template>
        </AppTableColumn>
        <AppTableColumn label="建议量" width="75" align="right" prop="suggestQty" />
        <AppTableColumn label="采购数量" width="125">
          <template #default="{ row }">
            <AppInputNumber v-model="row.quantity" :min="0.001" :precision="3" :controls="false" style="width: 105px" />
          </template>
        </AppTableColumn>
        <AppTableColumn label="采购单价" width="125">
          <template #default="{ row }">
            <AppInputNumber v-model="row.price" :min="0" :precision="2" :controls="false" style="width: 105px" />
          </template>
        </AppTableColumn>
        <AppTableColumn prop="unitName" label="单位" width="50" />
      </AppTable>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="generateDialog.visible = false" />
        <UButton :loading="generateDialog.saving" label="生成" @click="submitGenerate" />
      </template>
    </AppModal>

    <!-- ==================== 生成结果（FR-PUR-02） ==================== -->
    <AppModal v-model="resultDialog.visible" title="生成结果" width="620px">
      <template v-if="resultDialog.orders.length">
        <div class="result-title">已生成草稿采购单 {{ resultDialog.orders.length }} 张：</div>
        <AppTable :data="resultDialog.orders" size="small" stripe>
          <AppTableColumn prop="orderNo" label="单号" width="160" />
          <AppTableColumn prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
          <AppTableColumn prop="itemCount" label="商品数" width="80" align="right" />
          <AppTableColumn label="金额" width="110" align="right">
            <template #default="{ row }">￥{{ money(row.totalAmount) }}</template>
          </AppTableColumn>
        </AppTable>
      </template>
      <template v-if="resultDialog.skipped.length">
        <div class="result-title" style="margin-top: 14px">以下商品已跳过：</div>
        <AppTable :data="resultDialog.skipped" size="small" stripe>
          <AppTableColumn prop="goodsName" label="商品" min-width="160" show-overflow-tooltip />
          <AppTableColumn prop="reason" label="跳过原因" min-width="240" />
        </AppTable>
      </template>
      <div class="form-tip">草稿单据请在「采购管理 → 采购单」中检查明细后提交审核</div>
      <template #footer>
        <UButton label="知道了" @click="resultDialog.visible = false" />
      </template>
    </AppModal>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.card-header {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.qty-zero {
  color: var(--el-text-color-placeholder);
}

.basis {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.dialog-alert {
  margin-bottom: 12px;
}

.result-title {
  font-weight: 600;
  margin-bottom: 8px;
}

.form-tip {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>

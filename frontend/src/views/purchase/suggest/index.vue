<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  generateSuggestOrders,
  pageSuggestPurchase,
  type SuggestItemVO,
} from '@/api/purchase'
import { getCategoryTree, type CategoryNode } from '@/api/goods'

const auth = useAuthStore()

function money(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2)
}

// ==================== 建议采购清单（FR-PUR-02） ====================
const loading = ref(false)
const list = ref<SuggestItemVO[]>([])
const total = ref(0)
const categoryTree = ref<CategoryNode[]>([])
const query = reactive({
  name: '',
  goodsCode: '',
  categoryId: null as number | null,
  alertType: '' as string,
  page: 1,
  size: 20,
})

/** 行可选条件：建议量 > 0 且已维护默认供应商（FR-SUP-04） */
function selectable(row: SuggestItemVO) {
  return row.suggestQty > 0 && row.supplierId != null
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
    ElMessage.warning('请勾选需要采购的商品')
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
    supplierName: row.supplierName ?? '',
  }))
  generateDialog.visible = true
}

async function submitGenerate() {
  if (generateDialog.items.some((i) => i.quantity == null || i.quantity <= 0)) {
    ElMessage.warning('采购数量必须大于 0')
    return
  }
  generateDialog.saving = true
  try {
    const result = await generateSuggestOrders(
      generateDialog.items.map((i) => ({
        goodsId: i.goodsId,
        quantity: Number(i.quantity),
        price: i.price != null ? Number(i.price) : undefined,
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
})
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>建议采购量 = ceil(安全库存 × K + 日均销量 × 采购提前期 − 当前结存 − 在途采购量)，K/统计天数/提前期取系统参数（BR-INV-07）</span>
        </div>
      </template>

      <el-form inline @submit.prevent="search">
        <el-form-item label="商品">
          <el-input v-model="query.name" placeholder="商品名称" clearable style="width: 150px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="编码">
          <el-input v-model="query.goodsCode" placeholder="商品编码" clearable style="width: 130px" @keyup.enter="search" />
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
            style="width: 170px"
          />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.alertType" placeholder="全部" clearable style="width: 110px">
            <el-option label="缺货" value="OUT" />
            <el-option label="预警" value="WARNING" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
          <el-button
            v-if="auth.hasPerm('sys:pur:create')"
            type="success"
            :disabled="selection.length === 0"
            @click="openGenerate"
          >生成草稿采购单（已选 {{ selection.length }}）</el-button>
        </el-form-item>
      </el-form>

      <el-table
        v-loading="loading"
        :data="list"
        stripe
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="45" :selectable="selectable" />
        <el-table-column prop="goodsCode" label="编码" width="100" fixed="left" />
        <el-table-column prop="goodsName" label="商品" min-width="140" show-overflow-tooltip fixed="left" />
        <el-table-column label="类型" width="75" align="center">
          <template #default="{ row }">
            <el-tag :type="row.suggestType === 'OUT' ? 'danger' : 'warning'" size="small">
              {{ row.suggestType === 'OUT' ? '缺货' : '预警' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="categoryName" label="分类" width="95" show-overflow-tooltip />
        <el-table-column label="结存" width="75" align="right" prop="quantity" />
        <el-table-column label="安全下限" width="85" align="right" prop="safetyStockLower" />
        <el-table-column label="日均销量" width="85" align="right">
          <template #default="{ row }">{{ row.avgDailySales }}</template>
        </el-table-column>
        <el-table-column label="在途" width="70" align="right" prop="onWayQty" />
        <el-table-column label="提前期" width="70" align="center">
          <template #default="{ row }">{{ row.leadTimeDays }}天</template>
        </el-table-column>
        <el-table-column label="建议采购量" width="100" align="right">
          <template #default="{ row }">
            <span :class="{ 'qty-zero': row.suggestQty <= 0 }">{{ row.suggestQty }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="unitName" label="单位" width="55" />
        <el-table-column label="默认供应商" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.supplierName">{{ row.supplierName }}</span>
            <el-tag v-else type="info" size="small">未维护</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="供货价" width="80" align="right">
          <template #default="{ row }">{{ money(row.supplyPrice) }}</template>
        </el-table-column>
        <el-table-column label="最近采购价" width="95" align="right">
          <template #default="{ row }">{{ money(row.lastPurchasePrice) }}</template>
        </el-table-column>
        <el-table-column label="计算依据" min-width="280">
          <template #default="{ row }">
            <span class="basis">{{ row.calcBasis }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-row">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </el-card>

    <!-- ==================== 生成草稿确认（FR-PUR-02） ==================== -->
    <el-dialog v-model="generateDialog.visible" title="生成草稿采购单" width="760px">
      <el-alert type="info" :closable="false" show-icon title="按默认供应商自动分组生成草稿采购单（预计到货日期 = 今日 + 供应商提前期）；数量与单价可调整，保存后可在采购单列表继续编辑" class="dialog-alert" />
      <el-table :data="generateDialog.items" size="small" stripe max-height="360">
        <el-table-column prop="goodsName" label="商品" min-width="180" show-overflow-tooltip />
        <el-table-column prop="supplierName" label="默认供应商" width="120" show-overflow-tooltip />
        <el-table-column label="建议量" width="80" align="right" prop="suggestQty" />
        <el-table-column label="采购数量" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="0.001" :precision="3" :controls="false" style="width: 110px" />
          </template>
        </el-table-column>
        <el-table-column label="采购单价" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.price" :min="0" :precision="2" :controls="false" style="width: 110px" />
          </template>
        </el-table-column>
        <el-table-column prop="unitName" label="单位" width="55" />
      </el-table>
      <template #footer>
        <el-button @click="generateDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="generateDialog.saving" @click="submitGenerate">生成</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 生成结果（FR-PUR-02） ==================== -->
    <el-dialog v-model="resultDialog.visible" title="生成结果" width="620px">
      <template v-if="resultDialog.orders.length">
        <div class="result-title">已生成草稿采购单 {{ resultDialog.orders.length }} 张：</div>
        <el-table :data="resultDialog.orders" size="small" stripe>
          <el-table-column prop="orderNo" label="单号" width="160" />
          <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
          <el-table-column prop="itemCount" label="商品数" width="80" align="right" />
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }">￥{{ money(row.totalAmount) }}</template>
          </el-table-column>
        </el-table>
      </template>
      <template v-if="resultDialog.skipped.length">
        <div class="result-title" style="margin-top: 14px">以下商品已跳过：</div>
        <el-table :data="resultDialog.skipped" size="small" stripe>
          <el-table-column prop="goodsName" label="商品" min-width="160" show-overflow-tooltip />
          <el-table-column prop="reason" label="跳过原因" min-width="240" />
        </el-table>
      </template>
      <div class="form-tip">草稿单据请在「采购管理 → 采购单」中检查明细后提交审核</div>
      <template #footer>
        <el-button type="primary" @click="resultDialog.visible = false">知道了</el-button>
      </template>
    </el-dialog>
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

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  createReceiptOrder,
  getPurchaseOrderDetail,
  getReceiptOrderDetail,
  pageReceiptOrders,
  preCheckReceipt,
  type PurchaseOrderDetailVO,
  type PurchaseOrderItemVO,
  type ReceiptItemCheckVO,
  type ReceiptOrderVO,
  type ReceiptPreCheckVO,
} from '@/api/purchase'
import { pagePurchaseOrders, type PurchaseOrderVO } from '@/api/purchase'
import { pageSuppliers, type SupplierListVO } from '@/api/supplier'

const auth = useAuthStore()

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2)
}

// ---------- 供应商下拉 ----------
const suppliers = ref<SupplierListVO[]>([])

// ==================== 入库单列表（FR-PUR-07） ====================
const loading = ref(false)
const list = ref<ReceiptOrderVO[]>([])
const total = ref(0)
const dateRange = ref<[string, string] | null>(null)
const query = reactive({
  orderNo: '',
  purchaseOrderNo: '',
  supplierId: null as number | null,
  operatorName: '',
  page: 1,
  size: 20,
})

async function load() {
  loading.value = true
  try {
    const data = await pageReceiptOrders({
      orderNo: query.orderNo.trim() || undefined,
      purchaseOrderNo: query.purchaseOrderNo.trim() || undefined,
      supplierId: query.supplierId ?? undefined,
      operatorName: query.operatorName.trim() || undefined,
      beginDate: dateRange.value?.[0],
      endDate: dateRange.value?.[1],
      page: query.page,
      size: query.size,
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
  Object.assign(query, { orderNo: '', purchaseOrderNo: '', supplierId: null, operatorName: '', page: 1 })
  dateRange.value = null
  load()
}

// ==================== 入库单详情 + 打印（FR-PUR-07） ====================
const detailDrawer = reactive({
  visible: false,
  loading: false,
  data: null as ReceiptOrderVO | null,
})

async function openDetail(row: ReceiptOrderVO) {
  detailDrawer.visible = true
  detailDrawer.loading = true
  try {
    detailDrawer.data = await getReceiptOrderDetail(row.id)
  } finally {
    detailDrawer.loading = false
  }
}

/** A4 打印视图（FR-PUR-07）：含供应商、明细、合计、验收人、时间 */
function printReceipt() {
  const d = detailDrawer.data
  if (!d) return
  const rows = d.items
    .map(
      (i, idx) => `<tr>
        <td>${idx + 1}</td><td>${i.goodsCode}</td><td>${i.goodsName}</td><td>${i.spec ?? ''}</td>
        <td>${i.unitName ?? ''}</td><td class="r">${i.receivedQty}</td>
        <td class="r">${money(i.receivedPrice)}</td><td class="r">${money(i.amount)}</td><td>${i.remark ?? ''}</td>
      </tr>`,
    )
    .join('')
  const html = `<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8"><title>入库单 ${d.orderNo}</title>
  <style>
    body { font-family: "SimSun","Songti SC",serif; padding: 32px; color: #000; }
    h2 { text-align: center; margin: 0 0 4px; letter-spacing: 8px; }
    .no { text-align: center; color: #333; margin-bottom: 16px; }
    .meta { display: flex; justify-content: space-between; margin-bottom: 12px; font-size: 14px; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th, td { border: 1px solid #000; padding: 5px 8px; }
    .r { text-align: right; }
    .sign { margin-top: 24px; display: flex; justify-content: space-between; font-size: 14px; }
    .total { margin-top: 10px; text-align: right; font-size: 14px; }
    @page { size: A4; margin: 14mm; }
  </style></head><body>
    <h2>入库验收单</h2>
    <div class="no">单号：${d.orderNo}</div>
    <div class="meta">
      <span>原采购单：${d.purchaseOrderNo}</span>
      <span>供应商：${d.supplierName}</span>
      <span>验收时间：${fmtTime(d.receiptTime)}</span>
    </div>
    <table>
      <thead><tr><th>序号</th><th>编码</th><th>商品名称</th><th>规格</th><th>单位</th><th>实收数量</th><th>实收单价</th><th>金额</th><th>备注</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
    <div class="total">合计金额：￥${money(d.totalAmount)}</div>
    <div class="sign">
      <span>验收人：${d.operatorName}</span>
      <span>授权人：${d.authorizerName ?? '—'}</span>
      <span>送货单号：${d.deliveryOrderNo ?? '—'}</span>
      <span>打印时间：${new Date().toLocaleString('zh-CN')}</span>
    </div>
  </body></html>`
  const w = window.open('', '_blank', 'width=900,height=700')
  if (!w) {
    ElMessage.warning('浏览器拦截了打印窗口，请允许弹出窗口后重试')
    return
  }
  w.document.write(html)
  w.document.close()
  w.focus()
  w.print()
}

// ==================== 入库验收（FR-PUR-06） ====================
/** 第一步：选择可入库采购单（已审核待入库 / 部分入库） */
const pickDialog = reactive({
  visible: false,
  keyword: '',
  loading: false,
  list: [] as PurchaseOrderVO[],
})

function openPick() {
  pickDialog.keyword = ''
  pickDialog.visible = true
  searchPickable()
}

async function searchPickable() {
  pickDialog.loading = true
  try {
    // 分别查已审核待入库与部分入库（状态—操作约束矩阵）
    const [approved, partial] = await Promise.all([
      pagePurchaseOrders({ status: 'APPROVED', orderNo: pickDialog.keyword.trim() || undefined, page: 1, size: 50 }),
      pagePurchaseOrders({ status: 'PARTIAL', orderNo: pickDialog.keyword.trim() || undefined, page: 1, size: 50 }),
    ])
    pickDialog.list = [...approved.list, ...partial.list]
  } finally {
    pickDialog.loading = false
  }
}

/** 第二步：录入实收数量/单价，预检容差与价差（BR-PUR-04/05） */
interface ReceiveItem {
  purchaseItemId: number
  goodsName: string
  spec: string | null
  unitName: string | null
  pendingQty: number
  orderPrice: number
  receivedQty: number | null
  receivedPrice: number | null
  remark: string
}

const receiveDialog = reactive({
  visible: false,
  saving: false,
  orderId: 0,
  orderNo: '',
  supplierName: '',
  orderCreateBy: '',
  deliveryOrderNo: '',
  remark: '',
  authorizerName: '',
  items: [] as ReceiveItem[],
  precheck: null as ReceiptPreCheckVO | null,
})

async function openReceive(row: PurchaseOrderVO) {
  const detail: PurchaseOrderDetailVO = await getPurchaseOrderDetail(row.id)
  receiveDialog.orderId = row.id
  receiveDialog.orderNo = detail.orderNo
  receiveDialog.supplierName = detail.supplierName
  receiveDialog.orderCreateBy = detail.createBy
  receiveDialog.deliveryOrderNo = ''
  receiveDialog.remark = ''
  receiveDialog.authorizerName = ''
  receiveDialog.precheck = null
  receiveDialog.items = detail.items.map((i: PurchaseOrderItemVO) => ({
    purchaseItemId: i.id,
    goodsName: i.goodsName,
    spec: i.spec,
    unitName: i.unitName,
    pendingQty: Number(i.pendingQty),
    orderPrice: Number(i.price),
    receivedQty: Number(i.pendingQty),
    receivedPrice: Number(i.price),
    remark: '',
  }))
  pickDialog.visible = false
  receiveDialog.visible = true
}

/** 仅提交已填写数量的行 */
const filledItems = computed(() =>
  receiveDialog.items.filter((i) => i.receivedQty != null && i.receivedQty > 0),
)

function buildPayload() {
  return {
    purchaseOrderId: receiveDialog.orderId,
    deliveryOrderNo: receiveDialog.deliveryOrderNo.trim() || undefined,
    remark: receiveDialog.remark.trim() || undefined,
    authorizerName: receiveDialog.authorizerName.trim() || undefined,
    items: filledItems.value.map((i) => ({
      purchaseItemId: i.purchaseItemId,
      receivedQty: Number(i.receivedQty),
      receivedPrice: Number(i.receivedPrice),
      remark: i.remark || undefined,
    })),
  }
}

/** 预检（BR-PUR-04/05）：容差上限、价差授权、近 3 次均价黄色提示 */
async function doPreCheck() {
  if (filledItems.value.length === 0) {
    ElMessage.warning('请填写实收数量')
    return
  }
  receiveDialog.precheck = await preCheckReceipt(buildPayload())
}

/** 预检结果按明细行索引 */
const checkMap = computed(() => {
  const map = new Map<number, ReceiptItemCheckVO>()
  receiveDialog.precheck?.items.forEach((c) => map.set(c.purchaseItemId, c))
  return map
})

const needAuth = computed(() => receiveDialog.precheck?.items.some((c) => c.needAuth) ?? false)

async function submitReceive() {
  if (filledItems.value.length === 0) {
    ElMessage.warning('请填写实收数量')
    return
  }
  if (receiveDialog.precheck == null) {
    ElMessage.warning('请先执行预检，确认容差与价差提示（BR-PUR-04/05）')
    return
  }
  if (needAuth.value && !receiveDialog.authorizerName.trim()) {
    ElMessage.warning('存在超收或价差超容差行，须填写店长授权人（BR-PUR-04/05）')
    return
  }
  if (receiveDialog.orderCreateBy === auth.user?.realName) {
    ElMessage.warning('验收人与采购单创建人不能为同一人（职责分离，BR-SYS-01）')
    return
  }
  receiveDialog.saving = true
  try {
    const receipt = await createReceiptOrder(buildPayload())
    ElMessage.success(`入库单 ${receipt.orderNo} 已生成，库存已按实收单价联动`)
    receiveDialog.visible = false
    load()
  } finally {
    receiveDialog.saving = false
  }
}

onMounted(() => {
  load()
  pageSuppliers({ status: 1, page: 1, size: 200 }).then((data) => (suppliers.value = data.list))
})
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <el-form inline @submit.prevent="search">
        <el-form-item label="入库单号">
          <el-input v-model="query.orderNo" placeholder="RK 单号" clearable style="width: 155px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="采购单号">
          <el-input v-model="query.purchaseOrderNo" placeholder="PO 单号" clearable style="width: 155px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="供应商">
          <el-select v-model="query.supplierId" placeholder="全部" clearable filterable style="width: 160px">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作人">
          <el-input v-model="query.operatorName" placeholder="验收人" clearable style="width: 110px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
          <el-button v-if="auth.hasPerm('sys:rcv:create')" type="success" @click="openPick">新建验收</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="orderNo" label="入库单号" width="155" fixed="left" />
        <el-table-column prop="purchaseOrderNo" label="采购单号" width="155" />
        <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
        <el-table-column label="验收时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.receiptTime) }}</template>
        </el-table-column>
        <el-table-column prop="operatorName" label="验收人" width="90" />
        <el-table-column label="授权人" width="90">
          <template #default="{ row }">{{ row.authorizerName ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="deliveryOrderNo" label="送货单号" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.deliveryOrderNo ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="金额" width="110" align="right">
          <template #default="{ row }">￥{{ money(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row as ReceiptOrderVO)">详情</el-button>
            <el-button link type="primary" @click="openDetail(row as ReceiptOrderVO).then(printReceipt)">打印</el-button>
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

    <!-- ==================== 选择采购单（FR-PUR-06 第一步） ==================== -->
    <el-dialog v-model="pickDialog.visible" title="选择待入库采购单" width="680px">
      <el-form inline @submit.prevent="searchPickable">
        <el-form-item>
          <el-input v-model="pickDialog.keyword" placeholder="PO 单号" clearable style="width: 220px" @keyup.enter="searchPickable" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="searchPickable">查询</el-button>
        </el-form-item>
      </el-form>
      <el-table v-loading="pickDialog.loading" :data="pickDialog.list" size="small" stripe max-height="380">
        <el-table-column prop="orderNo" label="单号" width="155" />
        <el-table-column prop="supplierName" label="供应商" min-width="120" show-overflow-tooltip />
        <el-table-column prop="createBy" label="创建人" width="85" />
        <el-table-column prop="expectDate" label="预计到货" width="100" />
        <el-table-column label="入库进度" width="90" align="center">
          <template #default="{ row }">{{ row.receiveProgress ?? 0 }}%</template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.createBy === auth.user?.realName" @click="openReceive(row as PurchaseOrderVO)">验收</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="form-tip">仅显示"已审核待入库 / 部分入库"单据；创建人与验收人相同的单据不可验收（职责分离，BR-SYS-01）</div>
    </el-dialog>

    <!-- ==================== 录入实收（FR-PUR-06 第二步） ==================== -->
    <el-dialog v-model="receiveDialog.visible" :title="`入库验收 ${receiveDialog.orderNo}`" width="900px" :close-on-click-modal="false">
      <el-alert type="info" :closable="false" show-icon :title="`供应商：${receiveDialog.supplierName}；实收单价即存货成本（移动加权，BR-PUR-07）；超收超容差或价差超容差须店长授权`" class="dialog-alert" />
      <el-form label-width="90px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="送货单号">
              <el-input v-model="receiveDialog.deliveryOrderNo" maxlength="50" placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="验收备注">
              <el-input v-model="receiveDialog.remark" maxlength="200" placeholder="选填" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-table :data="receiveDialog.items" size="small" stripe max-height="320">
        <el-table-column label="商品" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.goodsName }}{{ row.spec ? `（${row.spec}）` : '' }}</template>
        </el-table-column>
        <el-table-column prop="unitName" label="单位" width="55" />
        <el-table-column label="待入库" width="75" align="right" prop="pendingQty" />
        <el-table-column label="采购单价" width="85" align="right">
          <template #default="{ row }">{{ money(row.orderPrice) }}</template>
        </el-table-column>
        <el-table-column label="实收数量" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.receivedQty" :min="0" :precision="3" :controls="false" style="width: 110px" />
          </template>
        </el-table-column>
        <el-table-column label="实收单价" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.receivedPrice" :min="0" :precision="2" :controls="false" style="width: 110px" />
          </template>
        </el-table-column>
        <el-table-column label="提示" width="200">
          <template #default="{ row }">
            <template v-if="checkMap.get(row.purchaseItemId)">
              <el-tag v-if="checkMap.get(row.purchaseItemId)!.overTolerance" type="danger" size="small" class="tag-gap">超收须授权</el-tag>
              <el-tag v-if="checkMap.get(row.purchaseItemId)!.priceAuthRequired" type="danger" size="small" class="tag-gap">价差须授权</el-tag>
              <el-tag v-if="checkMap.get(row.purchaseItemId)!.priceWarning" type="warning" size="small" class="tag-gap">
                偏离近3次均价
              </el-tag>
            </template>
            <span v-else class="sub">先预检</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="receiveDialog.precheck" class="form-tip">
        容差上限 = 待入库 ×(1+{{ receiveDialog.precheck.toleranceRate }}%)（BR-PUR-04）；
        与采购单价偏离超 ±{{ receiveDialog.precheck.priceDiffRate }}% 须授权（BR-PUR-05）；
        与近 3 次均价偏离超 ±{{ receiveDialog.precheck.priceWarnRate }}% 为黄色提示不阻断
      </div>
      <el-form v-if="needAuth" label-width="90px" @submit.prevent>
        <el-form-item label="店长授权人" required>
          <el-input v-model="receiveDialog.authorizerName" maxlength="30" placeholder="超收/价差超容差时必填（BR-PUR-04/05）" style="width: 240px" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="receiveDialog.visible = false">取消</el-button>
        <el-button type="warning" plain @click="doPreCheck">预检</el-button>
        <el-button type="primary" :loading="receiveDialog.saving" @click="submitReceive">提交验收</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 入库单详情（FR-PUR-07） ==================== -->
    <el-drawer v-model="detailDrawer.visible" title="入库单详情" size="680px">
      <div v-loading="detailDrawer.loading">
        <template v-if="detailDrawer.data">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="入库单号">{{ detailDrawer.data.orderNo }}</el-descriptions-item>
            <el-descriptions-item label="原采购单">{{ detailDrawer.data.purchaseOrderNo }}</el-descriptions-item>
            <el-descriptions-item label="供应商">{{ detailDrawer.data.supplierName }}</el-descriptions-item>
            <el-descriptions-item label="验收时间">{{ fmtTime(detailDrawer.data.receiptTime) }}</el-descriptions-item>
            <el-descriptions-item label="验收人">{{ detailDrawer.data.operatorName }}</el-descriptions-item>
            <el-descriptions-item label="授权人">{{ detailDrawer.data.authorizerName ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="送货单号">{{ detailDrawer.data.deliveryOrderNo ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="合计金额">￥{{ money(detailDrawer.data.totalAmount) }}</el-descriptions-item>
            <el-descriptions-item v-if="detailDrawer.data.remark" label="备注" :span="2">{{ detailDrawer.data.remark }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="detailDrawer.data.items" stripe size="small" style="margin-top: 12px">
            <el-table-column prop="goodsCode" label="编码" width="100" />
            <el-table-column prop="goodsName" label="商品" min-width="140" show-overflow-tooltip />
            <el-table-column prop="unitName" label="单位" width="60" />
            <el-table-column label="实收数量" width="85" align="right" prop="receivedQty" />
            <el-table-column label="实收单价" width="85" align="right">
              <template #default="{ row }">{{ money(row.receivedPrice) }}</template>
            </el-table-column>
            <el-table-column label="金额" width="90" align="right">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="100" show-overflow-tooltip />
          </el-table>
          <div class="print-btn-row">
            <el-button type="primary" @click="printReceipt">打印（A4）</el-button>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.dialog-alert {
  margin-bottom: 12px;
}

.form-tip {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.tag-gap {
  margin-right: 4px;
  margin-bottom: 2px;
}

.print-btn-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>

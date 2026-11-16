<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm } from '@/utils/confirm'
import { moneyText } from '@/utils/format'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import {
  createReceiptOrder,
  getPickablePurchaseOrderDetail,
  getReceiptOrderDetail,
  pagePickablePurchaseOrders,
  pageReceiptOrders,
  preCheckReceipt,
  type PurchaseOrderDetailVO,
  type PurchaseOrderItemVO,
  type ReceiptItemCheckVO,
  type ReceiptOrderItemVO,
  type ReceiptOrderVO,
  type ReceiptPreCheckVO,
} from '@/api/purchase'
import type { PurchaseOrderVO } from '@/api/purchase'
import { pageSuppliers, type SupplierListVO } from '@/api/supplier'
import { pMoney, pQty, printSheet } from '@/utils/print'
import { newRequestToken } from '@/utils/token'

const auth = useAuthStore()
const route = useRoute()

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
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
  printSheet<ReceiptOrderItemVO>({
    title: '入库验收单',
    meta: [
      `入库单号：${d.orderNo}`,
      `原采购单：${d.purchaseOrderNo}`,
      `供应商：${d.supplierName}`,
      `验收时间：${fmtTime(d.receiptTime)}`,
      `送货单号：${d.deliveryOrderNo ?? '—'}`,
      d.remark ? `备注：${d.remark}` : null,
    ],
    columns: [
      { label: '序号', value: (_r, i) => i + 1, align: 'center' },
      { label: '编码', value: (r) => r.goodsCode },
      { label: '商品名称', value: (r) => r.goodsName },
      { label: '规格', value: (r) => r.spec },
      { label: '单位', value: (r) => r.unitName, align: 'center' },
      { label: '实收数量', value: (r) => pQty(r.receivedQty), align: 'right' },
      { label: '实收单价', value: (r) => pMoney(r.receivedPrice), align: 'right' },
      { label: '金额', value: (r) => pMoney(r.amount), align: 'right' },
      { label: '备注', value: (r) => r.remark },
    ],
    rows: d.items,
    totals: [`合计金额：￥${pMoney(d.totalAmount)}`],
    signs: [`验收人：${d.operatorName}`, `授权人：${d.authorizerName ?? '—'}`],
  })
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
    // GAP-P0-05：按 sys:rcv:list 鉴权的"待入库单"专用接口（仅 APPROVED/PARTIAL），库管员无需采购单菜单权限
    const data = await pagePickablePurchaseOrders({
      orderNo: pickDialog.keyword.trim() || undefined,
      page: 1,
      size: 50,
    })
    pickDialog.list = data.list
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
  authorizerUsername: '',
  authorizerPassword: '',
  requestToken: '',
  items: [] as ReceiveItem[],
  precheck: null as ReceiptPreCheckVO | null,
})

async function openReceive(row: PurchaseOrderVO) {
  const detail: PurchaseOrderDetailVO = await getPickablePurchaseOrderDetail(row.id)
  receiveDialog.orderId = row.id
  receiveDialog.orderNo = detail.orderNo
  receiveDialog.supplierName = detail.supplierName
  receiveDialog.orderCreateBy = detail.createBy
  receiveDialog.deliveryOrderNo = ''
  receiveDialog.remark = ''
  receiveDialog.authorizerUsername = ''
  receiveDialog.authorizerPassword = ''
  // 每次进入验收生成请求令牌（NFR-REL-02）：失败重试复用同令牌，防重复入库
  receiveDialog.requestToken = newRequestToken()
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
    authorizerUsername: receiveDialog.authorizerUsername.trim() || undefined,
    authorizerPassword: receiveDialog.authorizerPassword || undefined,
    requestToken: receiveDialog.requestToken || undefined,
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
    notify.warning('请填写实收数量')
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
    notify.warning('请填写实收数量')
    return
  }
  if (receiveDialog.precheck == null) {
    notify.warning('请先执行预检，确认容差与价差提示（BR-PUR-04/05）')
    return
  }
  if (needAuth.value) {
    if (!receiveDialog.authorizerUsername.trim()) {
      notify.warning('存在超收或价差超容差行，须填写店长授权账号（BR-PUR-04/05）')
      return
    }
    if (!receiveDialog.authorizerPassword) {
      notify.warning('请填写授权店长密码以完成授权（FR-PUR-06）')
      return
    }
  }
  // 职责分离（BR-SYS-01，GAP-P1-08 统一策略）：验收人=创建人时后端按参数拒绝（严格）或放行并记异常日志，
  // 前端仅提示确认，不再硬拦截，使"放行+异常日志"路径可达（AC-13）
  if (receiveDialog.orderCreateBy === auth.user?.realName) {
    try {
      await confirm(
        '本单采购创建人与当前验收人为同一人：系统将按参数 duty_separation_strict 拒绝或放行并记录异常日志（BR-SYS-01）。是否继续提交？',
        '职责分离提醒',
        { type: 'warning', confirmButtonText: '继续提交', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  receiveDialog.saving = true
  try {
    const receipt = await createReceiptOrder(buildPayload())
    notify.success(`入库单 ${receipt.orderNo} 已生成，库存已按实收单价联动`)
    receiveDialog.visible = false
    load()
  } finally {
    receiveDialog.saving = false
  }
}

// 报表下钻深链（BR-RPT-06）：?orderNo=RO... 自动筛选并打开该单据详情
async function bootstrap() {
  const no = route.query.orderNo
  if (typeof no === 'string' && no) {
    query.orderNo = no
    await load()
    list.value = list.value.filter((o) => o.orderNo === no)
    const target = list.value[0]
    if (target) openDetail(target)
  } else {
    await load()
  }
}

onMounted(() => {
  bootstrap()
  // 供应商筛选项需要对供应商档案的只读权限（库管员无 sys:sup:list 时隐藏下拉，不影响验收主流程）
  if (auth.hasPerm('sys:sup:list')) {
    pageSuppliers({ status: 1, page: 1, size: 200 }).then((data) => (suppliers.value = data.list))
  }
})
</script>

<template>
  <div class="page">
    <UCard>
      <AppForm inline @submit.prevent="search">
        <AppFormItem label="入库单号">
          <AppInput v-model="query.orderNo" placeholder="RK 单号" clearable style="width: 155px" @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="采购单号">
          <AppInput v-model="query.purchaseOrderNo" placeholder="PO 单号" clearable style="width: 155px" @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem v-if="auth.hasPerm('sys:sup:list')" label="供应商">
          <AppSelect v-model="query.supplierId" placeholder="全部" clearable filterable style="width: 160px">
            <AppOption v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="操作人">
          <AppInput v-model="query.operatorName" placeholder="验收人" clearable style="width: 110px" @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="日期">
          <AppDatePicker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </AppFormItem>
        <AppFormItem>
          <div class="flex flex-wrap gap-2">
            <UButton label="查询" @click="search" />
            <UButton color="neutral" variant="outline" label="重置" @click="resetQuery" />
            <UButton v-if="auth.hasPerm('sys:rcv:create')" color="success" label="新建验收" @click="openPick" />
          </div>
        </AppFormItem>
      </AppForm>

      <AppTable :loading="loading" border :data="list" stripe>
        <AppTableColumn prop="orderNo" label="入库单号" width="155" fixed="left" />
        <AppTableColumn prop="purchaseOrderNo" label="采购单号" width="155" />
        <AppTableColumn prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
        <AppTableColumn label="验收时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.receiptTime) }}</template>
        </AppTableColumn>
        <AppTableColumn prop="operatorName" label="验收人" width="90" />
        <AppTableColumn label="授权人" width="90">
          <template #default="{ row }">{{ row.authorizerName ?? '—' }}</template>
        </AppTableColumn>
        <AppTableColumn prop="deliveryOrderNo" label="送货单号" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.deliveryOrderNo ?? '—' }}</template>
        </AppTableColumn>
        <AppTableColumn label="金额" width="110" align="right">
          <template #default="{ row }">￥{{ money(row.totalAmount) }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="140" fixed="right" align="center">
          <template #default="{ row }">
            <UButton variant="link" size="sm" label="详情" @click="openDetail(row as ReceiptOrderVO)" />
            <UButton variant="link" size="sm" label="打印" @click="openDetail(row as ReceiptOrderVO).then(printReceipt)" />
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

    <!-- ==================== 选择采购单（FR-PUR-06 第一步） ==================== -->
    <AppModal v-model="pickDialog.visible" title="选择待入库采购单" width="680px">
      <AppForm inline @submit.prevent="searchPickable">
        <AppFormItem>
          <AppInput v-model="pickDialog.keyword" placeholder="PO 单号" clearable style="width: 220px" @keyup.enter="searchPickable" />
        </AppFormItem>
        <AppFormItem>
          <UButton label="查询" @click="searchPickable" />
        </AppFormItem>
      </AppForm>
      <AppTable :loading="pickDialog.loading" :data="pickDialog.list" size="small" stripe max-height="380">
        <AppTableColumn prop="orderNo" label="单号" width="155" />
        <AppTableColumn prop="supplierName" label="供应商" min-width="120" show-overflow-tooltip />
        <AppTableColumn label="创建人" width="110">
          <template #default="{ row }">
            {{ row.createBy }}
            <UBadge v-if="row.createBy === auth.user?.realName" color="warning" variant="soft" size="sm" class="tag-gap">
              本人创建
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="expectDate" label="预计到货" width="100" />
        <AppTableColumn label="入库进度" width="90" align="center">
          <template #default="{ row }">{{ row.receiveProgress ?? 0 }}%</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="80" align="center">
          <template #default="{ row }">
            <UButton variant="link" size="sm" label="验收" @click="openReceive(row as PurchaseOrderVO)" />
          </template>
        </AppTableColumn>
      </AppTable>
      <div class="form-tip">仅显示"已审核待入库 / 部分入库"单据；创建人与验收人相同时按参数拒绝或放行并记录异常日志（职责分离，BR-SYS-01）</div>
    </AppModal>

    <!-- ==================== 录入实收（FR-PUR-06 第二步） ==================== -->
    <AppModal v-model="receiveDialog.visible" :title="`入库验收 ${receiveDialog.orderNo}`" width="900px" :close-on-click-modal="false">
      <UAlert color="info" variant="soft" icon="i-lucide-info" :title="`供应商：${receiveDialog.supplierName}；实收单价即存货成本（移动加权，BR-PUR-07）；超收超容差或价差超容差须店长授权`" class="dialog-alert" />
      <AppForm label-width="90px" @submit.prevent>
        <AppRow :gutter="12">
          <AppCol :span="8">
            <AppFormItem label="送货单号">
              <AppInput v-model="receiveDialog.deliveryOrderNo" maxlength="50" placeholder="选填" />
            </AppFormItem>
          </AppCol>
          <AppCol :span="16">
            <AppFormItem label="验收备注">
              <AppInput v-model="receiveDialog.remark" maxlength="200" placeholder="选填" />
            </AppFormItem>
          </AppCol>
        </AppRow>
      </AppForm>

      <AppTable :data="receiveDialog.items" size="small" stripe max-height="320">
        <AppTableColumn label="商品" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.goodsName }}{{ row.spec ? `（${row.spec}）` : '' }}</template>
        </AppTableColumn>
        <AppTableColumn prop="unitName" label="单位" width="55" />
        <AppTableColumn label="待入库" width="75" align="right" prop="pendingQty" />
        <AppTableColumn label="采购单价" width="85" align="right">
          <template #default="{ row }">{{ money(row.orderPrice) }}</template>
        </AppTableColumn>
        <AppTableColumn label="实收数量" width="130">
          <template #default="{ row }">
            <AppInputNumber v-model="row.receivedQty" :min="0" :precision="3" :controls="false" style="width: 110px" />
          </template>
        </AppTableColumn>
        <AppTableColumn label="实收单价" width="130" align="right">
          <template #default="{ row }">
            <AppInputNumber v-model="row.receivedPrice" :min="0" :precision="4" :controls="false" style="width: 110px" />
          </template>
        </AppTableColumn>
        <AppTableColumn label="提示" width="200">
          <template #default="{ row }">
            <template v-if="checkMap.get(row.purchaseItemId)">
              <UBadge v-if="checkMap.get(row.purchaseItemId)!.overTolerance" color="error" variant="soft" size="sm" class="tag-gap">超收须授权</UBadge>
              <UBadge v-if="checkMap.get(row.purchaseItemId)!.priceAuthRequired" color="error" variant="soft" size="sm" class="tag-gap">价差须授权</UBadge>
              <UBadge v-if="checkMap.get(row.purchaseItemId)!.priceWarning" color="warning" variant="soft" size="sm" class="tag-gap">
                偏离近3次均价
              </UBadge>
            </template>
            <span v-else class="sub">先预检</span>
          </template>
        </AppTableColumn>
      </AppTable>

      <div v-if="receiveDialog.precheck" class="form-tip">
        容差上限 = 待入库 ×(1+{{ receiveDialog.precheck.toleranceRate }}%)（BR-PUR-04）；
        与采购单价偏离超 ±{{ receiveDialog.precheck.priceDiffRate }}% 须授权（BR-PUR-05）；
        与近 3 次均价偏离超 ±{{ receiveDialog.precheck.priceWarnRate }}% 为黄色提示不阻断
      </div>
      <AppForm v-if="needAuth" label-width="90px" @submit.prevent>
        <AppFormItem label="授权账号" required>
          <AppInput v-model="receiveDialog.authorizerUsername" maxlength="30" placeholder="店长登录账号" style="width: 240px" />
        </AppFormItem>
        <AppFormItem label="授权密码" required>
          <AppInput
            v-model="receiveDialog.authorizerPassword"
            type="password"
            show-password
            maxlength="50"
            placeholder="店长账号密码（超收/价差超容差时必填，BR-PUR-04/05）"
            style="width: 240px"
          />
        </AppFormItem>
      </AppForm>

      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="receiveDialog.visible = false" />
        <UButton color="warning" variant="outline" label="预检" @click="doPreCheck" />
        <UButton :loading="receiveDialog.saving" label="提交验收" @click="submitReceive" />
      </template>
    </AppModal>

    <!-- ==================== 入库单详情（FR-PUR-07） ==================== -->
    <AppDrawer v-model="detailDrawer.visible" title="入库单详情" size="680px">
      <div v-loading="detailDrawer.loading">
        <template v-if="detailDrawer.data">
          <AppDescriptions :column="2" border size="small">
            <AppDescriptionsItem label="入库单号">{{ detailDrawer.data.orderNo }}</AppDescriptionsItem>
            <AppDescriptionsItem label="原采购单">{{ detailDrawer.data.purchaseOrderNo }}</AppDescriptionsItem>
            <AppDescriptionsItem label="供应商">{{ detailDrawer.data.supplierName }}</AppDescriptionsItem>
            <AppDescriptionsItem label="验收时间">{{ fmtTime(detailDrawer.data.receiptTime) }}</AppDescriptionsItem>
            <AppDescriptionsItem label="验收人">{{ detailDrawer.data.operatorName }}</AppDescriptionsItem>
            <AppDescriptionsItem label="授权人">{{ detailDrawer.data.authorizerName ?? '—' }}</AppDescriptionsItem>
            <AppDescriptionsItem label="送货单号">{{ detailDrawer.data.deliveryOrderNo ?? '—' }}</AppDescriptionsItem>
            <AppDescriptionsItem label="合计金额">￥{{ money(detailDrawer.data.totalAmount) }}</AppDescriptionsItem>
            <AppDescriptionsItem v-if="detailDrawer.data.remark" label="备注" :span="2">{{ detailDrawer.data.remark }}</AppDescriptionsItem>
          </AppDescriptions>
          <AppTable :data="detailDrawer.data.items" stripe size="small" style="margin-top: 12px">
            <AppTableColumn prop="goodsCode" label="编码" width="100" />
            <AppTableColumn prop="goodsName" label="商品" min-width="140" show-overflow-tooltip />
            <AppTableColumn prop="unitName" label="单位" width="60" />
            <AppTableColumn label="实收数量" width="85" align="right" prop="receivedQty" />
            <AppTableColumn label="实收单价" width="85" align="right">
              <template #default="{ row }">{{ money(row.receivedPrice) }}</template>
            </AppTableColumn>
            <AppTableColumn label="金额" width="90" align="right">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </AppTableColumn>
            <AppTableColumn prop="remark" label="备注" min-width="100" show-overflow-tooltip />
          </AppTable>
          <div class="print-btn-row">
            <UButton label="打印（A4）" @click="printReceipt" />
          </div>
        </template>
      </div>
    </AppDrawer>
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

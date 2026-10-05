<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { pageGoods, type GoodsListVO } from '@/api/goods'
import { getDictItems } from '@/api/system'
import {
  auditLossOrder,
  createLossOrder,
  getLossOrder,
  pageLossOrders,
  type LossBizType,
  type LossOrderVO,
  type LossStatus,
} from '@/api/inventory'

const auth = useAuthStore()

// ---------- 状态/枚举展示 ----------
const statusMap: Record<LossStatus, { label: string; type: 'primary' | 'success' | 'danger' }> = {
  PENDING: { label: '待审核', type: 'primary' },
  AUDITED: { label: '已审核', type: 'success' },
  REJECTED: { label: '已驳回', type: 'danger' },
}

const bizTypeMap: Record<LossBizType, { label: string; type: 'danger' | 'success' }> = {
  LOSS: { label: '报损', type: 'danger' },
  OVERFLOW: { label: '报溢', type: 'success' },
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

// ---------- 列表查询（FR-INV-10：单号/类型/状态/商品/申请人/时间区间） ----------
const loading = ref(false)
const list = ref<LossOrderVO[]>([])
const total = ref(0)
const query = reactive({
  orderNo: '',
  bizType: '' as string,
  status: '' as string,
  goodsKeyword: '',
  applyName: '',
  dateRange: null as [string, string] | null,
  page: 1,
  size: 20,
})

async function load() {
  loading.value = true
  try {
    const data = await pageLossOrders({
      orderNo: query.orderNo.trim() || undefined,
      bizType: query.bizType || undefined,
      status: query.status || undefined,
      goodsKeyword: query.goodsKeyword.trim() || undefined,
      applyName: query.applyName.trim() || undefined,
      beginDate: query.dateRange?.[0],
      endDate: query.dateRange?.[1],
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
  Object.assign(query, {
    orderNo: '',
    bizType: '',
    status: '',
    goodsKeyword: '',
    applyName: '',
    dateRange: null,
    page: 1,
  })
  load()
}

/** 原因选项：启用中的 loss_reason 字典项（FR-SYS-07，停用项不可选） */
const reasons = ref<{ label: string; value: string }[]>([])

onMounted(() => {
  load()
  getDictItems('loss_reason').then((items) => (reasons.value = items))
})

// ---------- 登记（FR-INV-10） ----------
const createVisible = ref(false)
const creating = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  bizType: 'LOSS' as LossBizType,
  goodsId: undefined as number | undefined,
  quantity: 1 as number | undefined,
  costPrice: undefined as number | undefined,
  reason: '',
  remark: '',
})

/** 商品远程搜索候选（名称 / 纯数字按条码），选中项保留在列表中用于回显 */
const goodsOptions = ref<GoodsListVO[]>([])
const goodsLoading = ref(false)

async function searchGoods(kw: string) {
  if (!kw.trim()) return
  goodsLoading.value = true
  try {
    const params = /^\d+$/.test(kw.trim()) ? { barcode: kw.trim() } : { name: kw.trim() }
    const data = await pageGoods({ ...params, status: 1, page: 1, size: 20 })
    const known = new Set(goodsOptions.value.map((g) => g.id))
    goodsOptions.value = [...goodsOptions.value, ...data.list.filter((g) => !known.has(g.id))]
  } finally {
    goodsLoading.value = false
  }
}

const selectedGoods = computed(
  () => goodsOptions.value.find((g) => g.id === form.goodsId) ?? null,
)

/** 切换类型：报溢默认带出当前成本（可修改），报损由服务端快照当前成本 */
function onBizTypeChange() {
  form.costPrice = form.bizType === 'OVERFLOW' ? (selectedGoods.value?.costPrice ?? undefined) : undefined
}

function onGoodsChange() {
  if (form.bizType === 'OVERFLOW' && form.costPrice == null) {
    form.costPrice = selectedGoods.value?.costPrice ?? undefined
  }
}

/** 金额预览（展示口径：报溢=录入价，报损=当前成本快照；服务端为权威口径） */
const previewCost = computed(() =>
  form.bizType === 'OVERFLOW' ? form.costPrice : selectedGoods.value?.costPrice,
)

const previewAmount = computed(() => {
  const q = form.quantity
  const c = previewCost.value
  return q == null || c == null ? null : Number((q * c).toFixed(2))
})

const rules = computed(() => ({
  goodsId: [{ required: true, message: '请搜索并选择商品', trigger: 'change' }],
  quantity: [
    {
      validator: (_r: unknown, v: number | undefined, cb: (e?: Error) => void) => {
        if (v == null || v <= 0) cb(new Error('数量必须大于 0'))
        else cb()
      },
      trigger: 'blur',
    },
  ],
  costPrice:
    form.bizType === 'OVERFLOW'
      ? [
          {
            validator: (_r: unknown, v: number | undefined, cb: (e?: Error) => void) => {
              if (v == null || v <= 0) cb(new Error('报溢必须录入大于 0 的成本单价'))
              else cb()
            },
            trigger: 'blur',
          },
        ]
      : [],
  reason: [{ required: true, message: '请选择原因', trigger: 'change' }],
}))

function openCreate() {
  Object.assign(form, {
    bizType: 'LOSS',
    goodsId: undefined,
    quantity: 1,
    costPrice: undefined,
    reason: '',
    remark: '',
  })
  goodsOptions.value = []
  createVisible.value = true
  // 复用表单实例时清掉上次遗留的校验红字（规则随类型联动变化）
  nextTick(() => formRef.value?.clearValidate())
}

async function saveCreate() {
  await formRef.value?.validate()
  creating.value = true
  try {
    const created = await createLossOrder({
      bizType: form.bizType,
      goodsId: form.goodsId!,
      quantity: form.quantity!,
      costPrice: form.bizType === 'OVERFLOW' ? form.costPrice : undefined,
      reason: form.reason,
      remark: form.remark.trim() || undefined,
    })
    createVisible.value = false
    ElMessage.success(`已登记 ${created.orderNo}，待店长审核`)
    load()
    openDetail(created.id)
  } catch {
    // 拦截器已提示
  } finally {
    creating.value = false
  }
}

// ---------- 详情（FR-INV-10） ----------
const drawer = reactive({ visible: false, id: 0 })
const detailLoading = ref(false)
const detail = ref<LossOrderVO | null>(null)

async function openDetail(id: number) {
  drawer.id = id
  drawer.visible = true
  await loadDetail()
}

async function loadDetail() {
  detailLoading.value = true
  try {
    detail.value = await getLossOrder(drawer.id)
  } finally {
    detailLoading.value = false
  }
}

// ---------- 审核（FR-INV-10：通过生成流水，驳回意见必填） ----------
const auditDialog = reactive({ visible: false, approved: true, remark: '' })
const auditing = ref(false)

function openAudit(approved: boolean) {
  auditDialog.approved = approved
  auditDialog.remark = ''
  auditDialog.visible = true
}

async function saveAudit() {
  const remark = auditDialog.remark.trim()
  if (!auditDialog.approved && !remark) {
    ElMessage.warning('驳回意见不能为空')
    return
  }
  auditing.value = true
  try {
    await auditLossOrder(drawer.id, auditDialog.approved, remark || undefined)
    auditDialog.visible = false
    ElMessage.success(
      auditDialog.approved
        ? detail.value?.bizType === 'LOSS'
          ? '审核通过，已生成报损出库流水'
          : '审核通过，已生成报溢入库流水'
        : '已驳回，单据不可修改，如需调整请重新登记',
    )
    await loadDetail()
    load()
  } catch {
    // 拦截器已提示
  } finally {
    auditing.value = false
  }
}
</script>

<template>
  <div class="page">
    <!-- 列表查询（FR-INV-10） -->
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="单号">
          <el-input v-model="query.orderNo" placeholder="单号模糊查询" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.bizType" clearable placeholder="全部" style="width: 100px">
            <el-option v-for="(b, k) in bizTypeMap" :key="k" :label="b.label" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 110px">
            <el-option v-for="(s, k) in statusMap" :key="k" :label="s.label" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="商品">
          <el-input v-model="query.goodsKeyword" placeholder="编码 / 条码 / 名称" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="申请人">
          <el-input v-model="query.applyName" placeholder="用户名" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="登记日期">
          <el-date-picker
            v-model="query.dateRange"
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
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-head">
          <span>报损报溢单</span>
          <el-button v-if="auth.hasPerm('sys:loss:create')" type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>登记报损/报溢
          </el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="orderNo" label="单号" width="150" />
        <el-table-column label="类型" width="76" align="center">
          <template #default="{ row }">
            <el-tag :type="bizTypeMap[row.bizType as LossBizType]?.type ?? 'info'" size="small">
              {{ bizTypeMap[row.bizType as LossBizType]?.label ?? row.bizType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="商品" min-width="200">
          <template #default="{ row }">
            <div class="goods-name">{{ row.goodsName }}</div>
            <div class="sub">
              {{ row.goodsCode }} / {{ row.barcode || '无条码' }}<template v-if="row.spec"> / {{ row.spec }}</template>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="categoryName" label="分类" width="96" />
        <el-table-column label="数量" width="110" align="right">
          <template #default="{ row }">{{ qty(row.quantity) }} {{ row.unitName }}</template>
        </el-table-column>
        <el-table-column label="成本单价" width="96" align="right">
          <template #default="{ row }">{{ money(row.costPrice) }}</template>
        </el-table-column>
        <el-table-column label="金额" width="100" align="right">
          <template #default="{ row }">
            <span :class="row.bizType === 'LOSS' ? 'shortage' : 'surplus'">{{ money(row.amount) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="reasonLabel" label="原因" width="100" />
        <el-table-column prop="applyName" label="申请人" width="90" />
        <el-table-column label="状态" width="92" align="center">
          <template #default="{ row }">
            <el-tag :type="statusMap[row.status as LossStatus]?.type ?? 'info'">
              {{ statusMap[row.status as LossStatus]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审核人 / 时间" width="170">
          <template #default="{ row }">
            <div v-if="row.auditorName">
              <div>{{ row.auditorName }}</div>
              <div class="sub">{{ fmtTime(row.auditTime) }}</div>
            </div>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">详情</el-button>
            <el-button
              v-if="row.status === 'PENDING' && auth.hasPerm('sys:loss:audit')"
              link
              type="success"
              @click="openDetail(row.id)"
            >审核</el-button>
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

    <!-- 登记（FR-INV-10） -->
    <el-dialog v-model="createVisible" title="登记报损/报溢" width="560px" :close-on-click-modal="false">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="登记后进入待审核，由店长审核；通过后生成库存流水并计入损耗报表（报损按当前成本结转，报溢按当前成本入账不重算均价）"
        class="dialog-alert"
      />
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
        :validate-on-rule-change="false"
      >
        <el-form-item label="业务类型" prop="bizType">
          <el-radio-group v-model="form.bizType" @change="onBizTypeChange">
            <el-radio value="LOSS">报损（出库）</el-radio>
            <el-radio value="OVERFLOW">报溢（入库）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="商品" prop="goodsId">
          <el-select
            v-model="form.goodsId"
            filterable
            remote
            :remote-method="searchGoods"
            :loading="goodsLoading"
            reserve-keyword
            placeholder="输入名称或条码搜索（仅启用商品）"
            style="width: 100%"
            @change="onGoodsChange"
          >
            <el-option
              v-for="g in goodsOptions"
              :key="g.id"
              :label="`${g.name}（${g.goodsCode}）`"
              :value="g.id"
            >
              <span>{{ g.name }}</span>
              <span class="option-sub">{{ g.goodsCode }} / 结存 {{ g.quantity }}</span>
            </el-option>
          </el-select>
          <div v-if="selectedGoods" class="form-tip">
            当前结存 {{ qty(selectedGoods.quantity) }} {{ selectedGoods.unitName }}｜当前成本单价
            {{ money(selectedGoods.costPrice) }}
          </div>
        </el-form-item>
        <el-form-item label="数量" prop="quantity">
          <el-input-number
            v-model="form.quantity"
            :precision="selectedGoods?.isWeighed === 1 ? 3 : 0"
            :step="selectedGoods?.isWeighed === 1 ? 0.001 : 1"
            :min="0"
            :max="99999999.999"
            style="width: 180px"
          />
          <span v-if="selectedGoods" class="form-tip unit-tip">{{ selectedGoods.unitName }}</span>
        </el-form-item>
        <el-form-item v-if="form.bizType === 'OVERFLOW'" label="成本单价" prop="costPrice">
          <el-input-number
            v-model="form.costPrice"
            :precision="4"
            :min="0"
            :max="99999999.9999"
            :controls="false"
            style="width: 180px"
          />
          <div class="form-tip tip-break">
            报溢须录入成本单价（默认带出当前成本，可修改）；库存按当前成本入账不重算均价
          </div>
        </el-form-item>
        <el-form-item v-else label="成本单价">
          <span class="cost-preview">{{ selectedGoods ? money(selectedGoods.costPrice) : '—' }}</span>
          <div class="form-tip tip-break">当前成本快照（登记时服务端重取），审核通过时按实际结转成本定稿</div>
        </el-form-item>
        <el-form-item label="金额预览">
          <span class="cost-preview">￥{{ previewAmount == null ? '—' : previewAmount.toFixed(2) }}</span>
        </el-form-item>
        <el-form-item label="原因" prop="reason">
          <el-select v-model="form.reason" placeholder="选择原因（数据字典）" style="width: 100%">
            <el-option v-for="r in reasons" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="200" show-word-limit placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="saveCreate">登记</el-button>
      </template>
    </el-dialog>

    <!-- 详情 / 审核（FR-INV-10） -->
    <el-drawer v-model="drawer.visible" :title="`报损报溢单 ${detail?.orderNo ?? ''}`" size="620px">
      <div v-loading="detailLoading" class="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="状态">
            <el-tag :type="statusMap[detail?.status as LossStatus]?.type ?? 'info'" size="small">
              {{ statusMap[detail?.status as LossStatus]?.label ?? detail?.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="类型">
            <el-tag :type="bizTypeMap[detail?.bizType as LossBizType]?.type ?? 'info'" size="small">
              {{ bizTypeMap[detail?.bizType as LossBizType]?.label ?? detail?.bizType }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="商品" :span="2">
            {{ detail?.goodsName }}
            <div class="sub">
              {{ detail?.goodsCode }} / {{ detail?.barcode || '无条码' }}
              <template v-if="detail?.spec"> / {{ detail.spec }}</template>
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="分类">{{ detail?.categoryName }}</el-descriptions-item>
          <el-descriptions-item label="单位">{{ detail?.unitName }}</el-descriptions-item>
          <el-descriptions-item label="数量">
            {{ qty(detail?.quantity) }} {{ detail?.unitName }}
          </el-descriptions-item>
          <el-descriptions-item label="成本单价">{{ money(detail?.costPrice) }}</el-descriptions-item>
          <el-descriptions-item label="金额">
            <span :class="detail?.bizType === 'LOSS' ? 'shortage' : 'surplus'">
              {{ money(detail?.amount) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="原因">{{ detail?.reasonLabel || '—' }}</el-descriptions-item>
          <el-descriptions-item label="申请人">{{ detail?.applyName }}</el-descriptions-item>
          <el-descriptions-item label="登记时间">{{ fmtTime(detail?.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="审核人">{{ detail?.auditorName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审核时间">{{ fmtTime(detail?.auditTime) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detail?.remark || '—' }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="detail?.status === 'PENDING'"
          type="info"
          :closable="false"
          show-icon
          title="待店长审核：通过后将生成库存流水（报损出库不足会被拒绝），已审核单据计入损耗报表"
          class="detail-alert"
        />
        <el-alert
          v-if="detail?.status === 'AUDITED' && detail?.bizType === 'LOSS'"
          type="success"
          :closable="false"
          show-icon
          title="已审核：金额按审核时实际结转成本定稿，与库存流水成本一致（BR-INV-06）"
          class="detail-alert"
        />
        <el-alert
          v-if="detail?.status === 'AUDITED' && detail?.auditRemark"
          type="success"
          :closable="false"
          show-icon
          :title="`审核意见：${detail.auditRemark}`"
          class="detail-alert"
        />
        <el-alert
          v-if="detail?.status === 'REJECTED'"
          type="warning"
          :closable="false"
          show-icon
          :title="`已驳回：${detail?.auditRemark ?? ''}（单据不可修改，如需调整请重新登记）`"
          class="detail-alert"
        />
      </div>

      <template #footer>
        <div class="drawer-footer">
          <div class="footer-tip" />
          <div>
            <el-button @click="drawer.visible = false">关闭</el-button>
            <el-button
              v-if="detail?.status === 'PENDING' && auth.hasPerm('sys:loss:audit')"
              type="success"
              @click="openAudit(true)"
            >审核通过</el-button>
            <el-button
              v-if="detail?.status === 'PENDING' && auth.hasPerm('sys:loss:audit')"
              type="danger"
              plain
              @click="openAudit(false)"
            >驳回</el-button>
          </div>
        </div>
      </template>
    </el-drawer>

    <!-- 审核对话框（FR-INV-10） -->
    <el-dialog
      v-model="auditDialog.visible"
      :title="auditDialog.approved ? '审核通过' : '驳回报损报溢单'"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-alert
        :type="auditDialog.approved ? 'success' : 'warning'"
        :closable="false"
        show-icon
        class="dialog-alert"
        :title="
          auditDialog.approved
            ? detail?.bizType === 'LOSS'
              ? '通过后将生成报损出库流水（按当前成本结转），库存不足会被拒绝（不可撤销）'
              : '通过后将生成报溢入库流水（按当前成本入账，不重算移动均价）'
            : '驳回后单据置为已驳回且不可修改，如需调整请重新登记'
        "
      />
      <el-input
        v-model="auditDialog.remark"
        type="textarea"
        :rows="3"
        maxlength="200"
        :placeholder="auditDialog.approved ? '审核意见（选填）' : '驳回意见（必填）'"
      />
      <template #footer>
        <el-button @click="auditDialog.visible = false">取消</el-button>
        <el-button
          :type="auditDialog.approved ? 'success' : 'danger'"
          :loading="auditing"
          @click="saveAudit"
        >
          {{ auditDialog.approved ? '确认通过' : '确认驳回' }}
        </el-button>
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
  }

  .goods-name {
    font-weight: 500;
  }

  .sub {
    font-size: 12px;
    color: #909399;
  }

  .surplus {
    color: #67c23a;
    font-weight: 600;
  }

  .shortage {
    color: #f56c6c;
    font-weight: 600;
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }

  .dialog-alert {
    margin-bottom: 14px;
  }

  .form-tip {
    font-size: 12px;
    color: #909399;
    line-height: 1.6;

    &.tip-break {
      flex-basis: 100%;
    }
  }

  .unit-tip {
    margin-left: 8px;
  }

  .option-sub {
    float: right;
    font-size: 12px;
    color: #909399;
  }

  .cost-preview {
    font-variant-numeric: tabular-nums;
    font-weight: 600;
  }

  .detail {
    .detail-alert {
      margin-top: 12px;
    }
  }

  .drawer-footer {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
}
</style>

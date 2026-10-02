<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { saveBlob } from '@/api/request'
import { getCategoryTree, pageGoods, type CategoryNode, type GoodsListVO } from '@/api/goods'
import { listDicts } from '@/api/system'
import {
  auditStockCheck,
  cancelStockCheck,
  createStockCheck,
  downloadCheckTemplate,
  enterStockCheck,
  getStockCheckDetail,
  importCheckActual,
  pageStockChecks,
  submitStockCheck,
  type CheckItemVO,
  type CheckOrderVO,
  type CheckStatus,
} from '@/api/inventory'
import { openPrintWindow, pMoney, pQty, pTime, printSheet } from '@/utils/print'

const auth = useAuthStore()

// ---------- 状态/枚举展示 ----------
const statusMap: Record<CheckStatus, { label: string; type: 'warning' | 'primary' | 'success' | 'info' }> = {
  CHECKING: { label: '盘点中', type: 'warning' },
  PENDING_AUDIT: { label: '待审核', type: 'primary' },
  AUDITED: { label: '已审核', type: 'success' },
  CANCELLED: { label: '已作废', type: 'info' },
}

const scopeTypeMap: Record<string, string> = {
  ALL: '全店',
  CATEGORY: '分类',
  GOODS: '指定商品',
  WARNING: '预警商品',
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

// ---------- 列表查询（FR-INV-07：单号/状态/盘点人/审核人/时间区间） ----------
const loading = ref(false)
const list = ref<CheckOrderVO[]>([])
const total = ref(0)
const query = reactive({
  orderNo: '',
  status: '' as string,
  checkerName: '',
  auditorName: '',
  dateRange: null as [string, string] | null,
  page: 1,
  size: 20,
})

async function load() {
  loading.value = true
  try {
    const data = await pageStockChecks({
      orderNo: query.orderNo.trim() || undefined,
      status: query.status || undefined,
      checkerName: query.checkerName.trim() || undefined,
      auditorName: query.auditorName.trim() || undefined,
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
  Object.assign(query, { orderNo: '', status: '', checkerName: '', auditorName: '', dateRange: null, page: 1 })
  load()
}

onMounted(() => {
  load()
  getCategoryTree().then((data) => (categoryTree.value = data))
  listDicts().then((dicts) => {
    const dict = dicts.find((d) => d.dictType === 'check_diff_reason')
    if (dict) {
      diffReasons.value = dict.items.filter((i) => i.status === 1).map((i) => i.label)
    }
  })
})

// ---------- 新建盘点单（FR-INV-07） ----------
const categoryTree = ref<CategoryNode[]>([])
const createVisible = ref(false)
const creating = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  scopeType: 'ALL' as 'ALL' | 'CATEGORY' | 'GOODS' | 'WARNING',
  categoryId: '' as number | '',
  goodsIds: [] as number[],
  remark: '',
})

/** 指定商品范围：远程搜索候选（名称 / 纯数字按条码），选项按选中保持 */
const goodsOptions = ref<GoodsListVO[]>([])
const goodsLoading = ref(false)

async function searchGoods(kw: string) {
  if (!kw.trim()) return
  goodsLoading.value = true
  try {
    // 纯数字优先按条码（扫码），否则按名称模糊（FR-SALE-02 同口径的简版）
    const params = /^\d+$/.test(kw.trim()) ? { barcode: kw.trim() } : { name: kw.trim() }
    const data = await pageGoods({ ...params, status: 1, page: 1, size: 50 })
    const known = new Set(goodsOptions.value.map((g) => g.id))
    goodsOptions.value = [...goodsOptions.value, ...data.list.filter((g) => !known.has(g.id))]
  } finally {
    goodsLoading.value = false
  }
}

const rules = computed(() => ({
  categoryId:
    form.scopeType === 'CATEGORY'
      ? [{ required: true, message: '请选择盘点分类', trigger: 'change' }]
      : [],
  goodsIds:
    form.scopeType === 'GOODS'
      ? [
          {
            validator: (_r: unknown, v: number[], cb: (e?: Error) => void) => {
              if (!v?.length) cb(new Error('请搜索并选择要盘点的商品'))
              else if (v.length > 500) cb(new Error('一次最多盘点 500 个商品'))
              else cb()
            },
            trigger: 'change',
          },
        ]
      : [],
}))

function openCreate() {
  Object.assign(form, { scopeType: 'ALL', categoryId: '', goodsIds: [], remark: '' })
  goodsOptions.value = []
  createVisible.value = true
}

async function saveCreate() {
  await formRef.value?.validate()
  creating.value = true
  try {
    const created = await createStockCheck({
      scopeType: form.scopeType,
      categoryId: form.scopeType === 'CATEGORY' ? (form.categoryId as number) : undefined,
      goodsIds: form.scopeType === 'GOODS' ? form.goodsIds : undefined,
      remark: form.remark.trim() || undefined,
    })
    createVisible.value = false
    load()
    try {
      await ElMessageBox.confirm(
        `盘点单 ${created.orderNo} 已创建（共 ${created.itemCount} 个商品），是否立即录入实盘数？`,
        '创建成功',
        { type: 'success', confirmButtonText: '去录入', cancelButtonText: '稍后' },
      )
      openDetail(created)
    } catch {
      // 稍后录入
    }
  } catch {
    // 拦截器已提示
  } finally {
    creating.value = false
  }
}

// ---------- 详情 / 实盘录入（FR-INV-08） ----------
const drawer = reactive({ visible: false, id: 0 })
const detailLoading = ref(false)
const order = ref<CheckOrderVO | null>(null)
const items = ref<CheckItemVO[]>([])
const itemsTotal = ref(0)
const itemsQuery = reactive({ page: 1, size: 20, onlyDiff: false, keyword: '' })

/** 本地未保存的录入（按 goodsId 暂存，翻页/筛选不丢失） */
interface EditLine {
  goodsId: number
  name: string
  bookQty: number
  costPrice: number
  actualQty: number | null
  diffReason: string
  remark: string
}
const edits = ref<Record<number, EditLine>>({})

const hasEdits = computed(() => Object.keys(edits.value).length > 0)
/** 仅盘点中且持录入权限可编辑 */
const editable = computed(() => order.value?.status === 'CHECKING' && auth.hasPerm('sys:check:update'))
const diffReasons = ref<string[]>([])
/** 审核人与盘点人职责分离（BR-SYS-01）：后端强校验，前端提前禁用 */
const selfCheck = computed(() => order.value?.checkerName === auth.user?.username)

function openDetail(row: CheckOrderVO) {
  if (drawer.id !== row.id) {
    // 切换单据：丢弃上一单的未保存录入
    edits.value = {}
    Object.assign(itemsQuery, { page: 1, size: 20, onlyDiff: false, keyword: '' })
  }
  drawer.id = row.id
  order.value = row
  drawer.visible = true
  loadDetail()
}

async function loadDetail() {
  detailLoading.value = true
  try {
    const data = await getStockCheckDetail(drawer.id, {
      page: itemsQuery.page,
      size: itemsQuery.size,
      onlyDiff: itemsQuery.onlyDiff,
      keyword: itemsQuery.keyword.trim() || undefined,
    })
    order.value = data.order
    items.value = data.items.list
    itemsTotal.value = data.items.total
  } finally {
    detailLoading.value = false
  }
}

function searchItems() {
  itemsQuery.page = 1
  loadDetail()
}

/** 差异表须整单打印：后端明细单页上限 100 行，按页取全差异行 */
async function fetchAllDiff(id: number): Promise<CheckItemVO[]> {
  const all: CheckItemVO[] = []
  for (let page = 1; ; page++) {
    const { items } = await getStockCheckDetail(id, { page, size: 100, onlyDiff: true })
    all.push(...items.list)
    if (!items.list.length || all.length >= items.total) return all
  }
}

const printing = ref(false)

/** 打印盘点差异表（FR-RPT-09、§2.1.4）：A4 横向，只出差异行，附签署栏 */
function printDiff() {
  const o = order.value
  if (!o) return
  const win = openPrintWindow('A4')
  if (!win) return
  printing.value = true
  fetchAllDiff(o.id)
    .then((rows) =>
      printSheet<CheckItemVO>(
        {
          title: '盘点差异表',
          orientation: 'landscape',
          meta: [
            `盘点单号：${o.orderNo}`,
            `盘点范围：${scopeTypeMap[o.scopeType] ?? o.scopeType}｜${o.scopeDesc}`,
            `状态：${statusMap[o.status]?.label ?? o.status}`,
            `账面基准时间：${pTime(o.baseTime)}｜开始盘点：${pTime(o.startTime)}`,
            `盘点人：${o.checkerName}｜审核人：${o.auditorName ?? '—'}｜审核时间：${pTime(o.auditTime)}`,
            o.duringChangeCount > 0
              ? `注意：盘点期间发生 ${o.duringChangeCount} 笔出入库，差异按账面基准时刻快照计算（BR-INV-10）`
              : null,
            o.auditRemark ? `审核意见：${o.auditRemark}` : null,
          ],
          columns: [
            { label: '序号', value: (_r, i) => i + 1, align: 'center' },
            { label: '商品编码', value: (r) => r.goodsCode },
            { label: '商品名称', value: (r) => r.name },
            { label: '分类', value: (r) => r.categoryName },
            { label: '规格', value: (r) => r.spec },
            { label: '单位', value: (r) => r.unitName, align: 'center' },
            { label: '账面数量', value: (r) => pQty(r.bookQty), align: 'right' },
            { label: '实盘数量', value: (r) => pQty(r.actualQty), align: 'right' },
            { label: '差异数量', value: (r) => pQty(r.diffQty), align: 'right' },
            { label: '成本单价', value: (r) => pMoney(r.costPrice), align: 'right' },
            { label: '差异金额', value: (r) => pMoney(r.diffAmount), align: 'right' },
            { label: '差异原因', value: (r) => r.diffReason },
            { label: '备注', value: (r) => r.remark },
          ],
          rows,
          totals: [
            `差异行数：${rows.length}`,
            `盘盈金额：￥${pMoney(o.surplusAmount)}`,
            `盘亏金额：￥${pMoney(o.shortageAmount)}`,
            `差异净额：￥${pMoney(Number(o.surplusAmount) - Number(o.shortageAmount))}`,
          ],
          signs: ['盘点人：____________', '审核人：____________', '店长：____________'],
        },
        win,
      ),
    )
    .catch(() => win.close())
    .finally(() => {
      printing.value = false
    })
}

/** 关闭前提醒未保存录入 */
function guardClose(done: () => void) {
  if (!hasEdits.value) {
    done()
    return
  }
  ElMessageBox.confirm('有未保存的实盘数录入，关闭后将丢失，确定关闭？', '未保存提醒', {
    type: 'warning',
  })
    .then(() => done())
    .catch(() => undefined)
}

function ensureEdit(row: CheckItemVO): EditLine {
  if (!edits.value[row.goodsId]) {
    edits.value[row.goodsId] = {
      goodsId: row.goodsId,
      name: row.name,
      bookQty: row.bookQty,
      costPrice: row.costPrice,
      actualQty: row.actualQty,
      diffReason: row.diffReason ?? '',
      remark: row.remark ?? '',
    }
  }
  return edits.value[row.goodsId]
}

function displayActual(row: CheckItemVO) {
  return edits.value[row.goodsId]?.actualQty ?? row.actualQty
}

function displayReason(row: CheckItemVO) {
  return edits.value[row.goodsId]?.diffReason ?? row.diffReason ?? ''
}

function displayRemark(row: CheckItemVO) {
  return edits.value[row.goodsId]?.remark ?? row.remark ?? ''
}

/** 差异即时计算（展示用，服务端为权威口径） */
function calcDiff(row: CheckItemVO): number | null {
  const a = displayActual(row)
  return a == null ? null : Number((a - row.bookQty).toFixed(3))
}

function calcDiffAmount(row: CheckItemVO): number | null {
  const d = calcDiff(row)
  return d == null || d === 0 ? d : Number((d * row.costPrice).toFixed(2))
}

function setActual(row: CheckItemVO, v: number | null | undefined) {
  ensureEdit(row).actualQty = v ?? null
}

function setReason(row: CheckItemVO, v: string) {
  ensureEdit(row).diffReason = v ?? ''
}

function setRemark(row: CheckItemVO, v: string) {
  ensureEdit(row).remark = v ?? ''
}

const saving = ref(false)

/** 保存录入（FR-INV-08）：差异行原因必填（客户端预检 + 服务端强校验），批量分片提交 */
async function saveEntry() {
  const lines = Object.values(edits.value).filter((e) => e.actualQty != null)
  if (!lines.length) {
    ElMessage.warning('请先录入实盘数量')
    return
  }
  const noReason = lines.filter(
    (e) => Math.abs(e.actualQty! - e.bookQty) > 1e-9 && !e.diffReason.trim(),
  )
  if (noReason.length) {
    ElMessage.error(`以下商品实盘与账面存在差异，必须填写原因：${noReason.map((e) => e.name).join('、')}`)
    return
  }
  saving.value = true
  try {
    let summary = null
    for (let i = 0; i < lines.length; i += 500) {
      summary = await enterStockCheck(drawer.id, {
        items: lines.slice(i, i + 500).map((e) => ({
          goodsId: e.goodsId,
          actualQty: e.actualQty!,
          diffReason: e.diffReason.trim() || undefined,
          remark: e.remark.trim() || undefined,
        })),
      })
    }
    edits.value = {}
    ElMessage.success(
      `已保存 ${lines.length} 行实盘数：盘盈 ${money(summary?.surplusAmount ?? 0)} / 盘亏 ${money(summary?.shortageAmount ?? 0)}`,
    )
    await loadDetail()
    load()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

// ---------- 提交审核（FR-INV-08：未盘行按账面确认） ----------
const submitting = ref(false)

async function doSubmit(id: number) {
  if (drawer.visible && drawer.id === id && hasEdits.value) {
    ElMessage.warning('请先保存已录入的实盘数')
    return
  }
  await ElMessageBox.confirm(
    '提交后未录入实盘的行将按账面数量确认，单据进入待审核状态，确定提交？',
    '提交审核',
    { type: 'warning' },
  )
  submitting.value = true
  try {
    const summary = await submitStockCheck(id)
    ElMessage.success(
      `已提交审核：盘盈 ${money(summary.surplusAmount)} / 盘亏 ${money(summary.shortageAmount)}`,
    )
    await refreshAfter(id)
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

// ---------- 审核（FR-INV-09：通过覆盖结存，驳回退回盘点中） ----------
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
    await auditStockCheck(drawer.id, auditDialog.approved, remark || undefined)
    auditDialog.visible = false
    ElMessage.success(auditDialog.approved ? '审核通过，库存已按实盘数调整' : '已驳回，单据退回盘点中')
    await loadDetail()
    load()
  } catch {
    // 拦截器已提示
  } finally {
    auditing.value = false
  }
}

// ---------- 作废（§2.2.4：仅盘点中，提交审核前） ----------
async function doCancel(id: number) {
  const { value } = await ElMessageBox.prompt(
    '作废后不可恢复（仅盘点中的单据可作废），请填写作废原因：',
    `作废盘点单 ${order.value?.orderNo ?? ''}`,
    {
      type: 'warning',
      inputPlaceholder: '作废原因（必填）',
      inputValidator: (v: string) => !!v?.trim() || '作废原因不能为空',
    },
  )
  await cancelStockCheck(id, value.trim())
  ElMessage.success('已作废')
  await refreshAfter(id)
}

/** 单据状态变化后：刷新列表与打开中的抽屉 */
async function refreshAfter(id: number) {
  if (drawer.visible && drawer.id === id) {
    await loadDetail()
  }
  load()
}

// ---------- 模板下载 / Excel 导入（FR-INV-08） ----------
const downloading = ref(false)

async function downloadTemplate() {
  downloading.value = true
  try {
    const blob = await downloadCheckTemplate(drawer.id)
    saveBlob(blob, `盘点录入-${order.value?.orderNo ?? drawer.id}.xlsx`)
  } finally {
    downloading.value = false
  }
}

const fileInputRef = ref<HTMLInputElement>()
const importing = ref(false)

function pickImportFile() {
  fileInputRef.value?.click()
}

async function onImportFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  importing.value = true
  try {
    const res = await importCheckActual(drawer.id, file)
    edits.value = {}
    ElMessage.success(
      `成功导入 ${res.importedCount} 行实盘数：盘盈 ${money(res.summary.surplusAmount)} / 盘亏 ${money(res.summary.shortageAmount)}`,
    )
    await loadDetail()
    load()
  } catch {
    // 拦截器已提示
  } finally {
    importing.value = false
    input.value = ''
  }
}
</script>

<template>
  <div class="page">
    <!-- 列表查询（FR-INV-07） -->
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="盘点单号">
          <el-input v-model="query.orderNo" placeholder="单号模糊查询" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 110px">
            <el-option v-for="(s, k) in statusMap" :key="k" :label="s.label" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="盘点人">
          <el-input v-model="query.checkerName" placeholder="用户名" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="审核人">
          <el-input v-model="query.auditorName" placeholder="用户名" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="盘点日期">
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
          <span>盘点单</span>
          <el-button
            v-if="auth.hasPerm('sys:check:create')"
            type="primary"
            @click="openCreate"
          >
            <el-icon><Plus /></el-icon>新建盘点单
          </el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="orderNo" label="盘点单号" width="140" />
        <el-table-column label="盘点范围" min-width="200">
          <template #default="{ row }">
            <div class="scope-cell">
              <el-tag size="small" effect="plain">{{ scopeTypeMap[row.scopeType] ?? row.scopeType }}</el-tag>
              <span class="scope-desc">{{ row.scopeDesc }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="92" align="center">
          <template #default="{ row }">
            <el-tag :type="statusMap[row.status as CheckStatus]?.type ?? 'info'">
              {{ statusMap[row.status as CheckStatus]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="checkerName" label="盘点人" width="90" />
        <el-table-column label="开始时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="录入进度" width="150">
          <template #default="{ row }">
            <span class="qty">{{ row.enteredCount }}/{{ row.itemCount }}</span>
            <el-tag v-if="row.diffCount > 0" type="danger" size="small" class="diff-tag">
              差异 {{ row.diffCount }} 行
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="盘盈金额" width="96" align="right">
          <template #default="{ row }">
            <span :class="Number(row.surplusAmount) > 0 ? 'surplus' : ''">{{ money(row.surplusAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="盘亏金额" width="96" align="right">
          <template #default="{ row }">
            <span :class="Number(row.shortageAmount) > 0 ? 'shortage' : ''">{{ money(row.shortageAmount) }}</span>
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
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row as CheckOrderVO)">
              {{ row.status === 'CHECKING' ? '录入' : '详情' }}
            </el-button>
            <el-button
              v-if="row.status === 'CHECKING' && auth.hasPerm('sys:check:submit')"
              link
              type="primary"
              @click="doSubmit(row.id)"
            >提交审核</el-button>
            <el-button
              v-if="row.status === 'PENDING_AUDIT' && auth.hasPerm('sys:check:audit')"
              link
              type="success"
              @click="openDetail(row as CheckOrderVO)"
            >审核</el-button>
            <el-button
              v-if="row.status === 'CHECKING' && auth.hasPerm('sys:check:cancel')"
              link
              type="danger"
              @click="doCancel(row.id)"
            >作废</el-button>
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

    <!-- 新建盘点单（FR-INV-07） -->
    <el-dialog v-model="createVisible" title="新建盘点单" width="560px" :close-on-click-modal="false">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="创建时系统快照账面数量作为盘点基准；同一商品存在未审核完成的盘点单时不可重复创建（BR-INV-09）"
        class="dialog-alert"
      />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="盘点范围" prop="scopeType">
          <el-radio-group v-model="form.scopeType">
            <el-radio value="ALL">全店</el-radio>
            <el-radio value="CATEGORY">分类</el-radio>
            <el-radio value="GOODS">指定商品</el-radio>
            <el-radio value="WARNING">预警商品</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.scopeType === 'CATEGORY'" label="盘点分类" prop="categoryId">
          <el-tree-select
            v-model="form.categoryId"
            :data="categoryTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            check-strictly
            clearable
            default-expand-all
            placeholder="选择分类（含其子分类）"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item v-if="form.scopeType === 'GOODS'" label="选择商品" prop="goodsIds">
          <el-select
            v-model="form.goodsIds"
            multiple
            filterable
            remote
            :remote-method="searchGoods"
            :loading="goodsLoading"
            reserve-keyword
            placeholder="输入名称或条码搜索，最多 500 个"
            style="width: 100%"
          >
            <el-option
              v-for="g in goodsOptions"
              :key="g.id"
              :label="`${g.name}（${g.goodsCode}）`"
              :value="g.id"
            >
              <span>{{ g.name }}</span>
              <span class="option-sub">{{ g.goodsCode }} / {{ g.barcode || '无条码' }}</span>
            </el-option>
          </el-select>
          <div class="form-tip">已选 {{ form.goodsIds.length }} 个商品</div>
        </el-form-item>
        <el-form-item v-if="form.scopeType === 'WARNING'" label="范围说明">
          <span class="form-tip">自动纳入缺货 / 预警 / 积压状态的商品</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="100" placeholder="选填，拼入范围描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="saveCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 详情 / 实盘录入抽屉（FR-INV-08） -->
    <el-drawer
      v-model="drawer.visible"
      :title="`盘点单 ${order?.orderNo ?? ''}`"
      size="92%"
      :before-close="guardClose"
    >
      <div v-loading="detailLoading" class="detail">
        <!-- 单头信息 -->
        <el-descriptions :column="4" border size="small" class="head">
          <el-descriptions-item label="状态">
            <el-tag :type="statusMap[order?.status as CheckStatus]?.type ?? 'info'" size="small">
              {{ statusMap[order?.status as CheckStatus]?.label ?? order?.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="盘点范围" :span="2">{{ order?.scopeDesc }}</el-descriptions-item>
          <el-descriptions-item label="盘点人">{{ order?.checkerName }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ fmtTime(order?.startTime) }}</el-descriptions-item>
          <el-descriptions-item label="账面基准时间">{{ fmtTime(order?.baseTime) }}</el-descriptions-item>
          <el-descriptions-item label="审核人">{{ order?.auditorName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审核时间">{{ fmtTime(order?.auditTime) }}</el-descriptions-item>
          <el-descriptions-item v-if="order?.auditRemark" label="审核意见" :span="2">
            {{ order.auditRemark }}
          </el-descriptions-item>
          <el-descriptions-item v-if="order?.cancelReason" label="作废原因" :span="2">
            {{ order.cancelReason }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- 汇总指标（FR-INV-08 实时口径） -->
        <div class="stats">
          <div class="stat">
            <div class="stat-label">录入进度</div>
            <div class="stat-value">{{ order?.enteredCount }}/{{ order?.itemCount }}</div>
          </div>
          <div class="stat">
            <div class="stat-label">差异行数</div>
            <div class="stat-value" :class="{ shortage: (order?.diffCount ?? 0) > 0 }">
              {{ order?.diffCount ?? 0 }}
            </div>
          </div>
          <div class="stat">
            <div class="stat-label">盘盈金额</div>
            <div class="stat-value surplus">{{ money(order?.surplusAmount) }}</div>
          </div>
          <div class="stat">
            <div class="stat-label">盘亏金额</div>
            <div class="stat-value shortage">{{ money(order?.shortageAmount) }}</div>
          </div>
        </div>

        <!-- 提示区 -->
        <el-alert
          v-if="order?.status === 'CHECKING' && order.auditRemark"
          type="warning"
          :closable="false"
          show-icon
          :title="`审核驳回：${order.auditRemark}（单据已退回盘点中，录入数据已保留）`"
          class="detail-alert"
        />
        <el-alert
          v-if="order?.status === 'AUDITED' && (order.duringChangeCount ?? 0) > 0"
          type="info"
          :closable="false"
          show-icon
          :title="`盘点期间发生 ${order.duringChangeCount} 笔出入库，差异以审核时结存为准（BR-INV-10）`"
          class="detail-alert"
        />
        <el-alert
          v-if="order?.status === 'CHECKING'"
          type="info"
          :closable="false"
          show-icon
          title="未录入实盘的行提交审核时按账面数量确认；实盘与账面存在差异的行必须填写差异原因"
          class="detail-alert"
        />

        <!-- 明细工具栏 -->
        <div class="toolbar">
          <el-input
            v-model="itemsQuery.keyword"
            placeholder="扫码 / 编码 / 条码 / 名称定位行"
            clearable
            style="width: 240px"
            @keyup.enter="searchItems"
            @clear="searchItems"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-checkbox v-model="itemsQuery.onlyDiff" @change="searchItems">只看差异行</el-checkbox>
          <div class="toolbar-spacer" />
          <template v-if="editable">
            <el-button :loading="downloading" @click="downloadTemplate">
              <el-icon><Download /></el-icon>下载模板
            </el-button>
            <el-button :loading="importing" @click="pickImportFile">
              <el-icon><Upload /></el-icon>Excel 导入
            </el-button>
            <input
              ref="fileInputRef"
              type="file"
              accept=".xlsx"
              hidden
              @change="onImportFile"
            />
          </template>
        </div>

        <!-- 明细表（FR-INV-08：逐行录入，差异实时计算） -->
        <el-table :data="items" stripe size="small" :empty-text="itemsQuery.onlyDiff ? '没有差异行' : '暂无数据'">
          <el-table-column label="商品" min-width="200" fixed="left">
            <template #default="{ row }">
              <div class="goods-name">{{ row.name }}</div>
              <div class="sub">{{ row.goodsCode }} / {{ row.barcode || '无条码' }}<template v-if="row.spec"> / {{ row.spec }}</template></div>
            </template>
          </el-table-column>
          <el-table-column prop="categoryName" label="分类" width="96" />
          <el-table-column prop="unitName" label="单位" width="56" align="center" />
          <el-table-column prop="bookQty" label="账面数量" width="92" align="right">
            <template #default="{ row }">{{ qty(row.bookQty) }}</template>
          </el-table-column>
          <el-table-column label="实盘数量" width="130" align="center">
            <template #default="{ row }">
              <el-input-number
                v-if="editable"
                :model-value="displayActual(row as CheckItemVO)"
                :precision="(row as CheckItemVO).isWeighed === 1 ? 3 : 0"
                :step="(row as CheckItemVO).isWeighed === 1 ? 0.001 : 1"
                :min="0"
                :max="99999999.999"
                :controls="false"
                placeholder="未盘"
                class="qty-input"
                @update:model-value="(v: number | undefined) => setActual(row as CheckItemVO, v)"
              />
              <span v-else>{{ row.actualQty == null ? '未盘' : qty(row.actualQty) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="差异数量" width="92" align="right">
            <template #default="{ row }">
              <span v-if="calcDiff(row as CheckItemVO) != null" :class="calcDiff(row as CheckItemVO)! > 0 ? 'surplus' : calcDiff(row as CheckItemVO)! < 0 ? 'shortage' : ''">
                {{ calcDiff(row as CheckItemVO)!.toFixed(3).replace(/\.?0+$/, '') || '0' }}
              </span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="差异金额" width="88" align="right">
            <template #default="{ row }">
              <span v-if="calcDiffAmount(row as CheckItemVO) != null" :class="calcDiffAmount(row as CheckItemVO)! > 0 ? 'surplus' : calcDiffAmount(row as CheckItemVO)! < 0 ? 'shortage' : ''">
                {{ money(calcDiffAmount(row as CheckItemVO)) }}
              </span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="差异原因" width="150">
            <template #default="{ row }">
              <el-select
                v-if="editable"
                :model-value="displayReason(row as CheckItemVO)"
                filterable
                allow-create
                default-first-option
                placeholder="差异≠0 必填"
                size="small"
                class="reason-select"
                @update:model-value="(v: string) => setReason(row as CheckItemVO, v)"
              >
                <el-option v-for="r in diffReasons" :key="r" :label="r" :value="r" />
              </el-select>
              <span v-else>{{ row.diffReason || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="备注" width="130">
            <template #default="{ row }">
              <el-input
                v-if="editable"
                :model-value="displayRemark(row as CheckItemVO)"
                size="small"
                maxlength="100"
                placeholder="选填"
                @update:model-value="(v: string) => setRemark(row as CheckItemVO, v)"
              />
              <span v-else>{{ row.remark || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-model:current-page="itemsQuery.page"
          v-model:page-size="itemsQuery.size"
          :total="itemsTotal"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          class="pager"
          @change="loadDetail"
        />
      </div>

      <!-- 抽屉底部操作（按状态与权限渲染） -->
      <template #footer>
        <div class="drawer-footer">
          <div class="footer-tip">
            <template v-if="hasEdits">有 {{ Object.keys(edits).length }} 行未保存的录入</template>
          </div>
          <div>
            <el-button :loading="printing" @click="printDiff">打印差异表</el-button>
            <el-button v-if="editable" :loading="saving" type="primary" @click="saveEntry">
              保存录入
            </el-button>
            <el-button
              v-if="order?.status === 'CHECKING' && auth.hasPerm('sys:check:submit')"
              :loading="submitting"
              type="warning"
              plain
              @click="doSubmit(drawer.id)"
            >提交审核</el-button>
            <el-button
              v-if="order?.status === 'CHECKING' && auth.hasPerm('sys:check:cancel')"
              type="danger"
              plain
              @click="doCancel(drawer.id)"
            >作废</el-button>
            <el-button
              v-if="order?.status === 'PENDING_AUDIT' && auth.hasPerm('sys:check:audit') && !selfCheck"
              type="success"
              @click="openAudit(true)"
            >审核通过</el-button>
            <el-button
              v-if="order?.status === 'PENDING_AUDIT' && auth.hasPerm('sys:check:audit') && !selfCheck"
              type="danger"
              plain
              @click="openAudit(false)"
            >驳回</el-button>
            <el-tag v-if="order?.status === 'PENDING_AUDIT' && selfCheck" type="info">
              本人盘点的单据不可自审（BR-SYS-01）
            </el-tag>
          </div>
        </div>
      </template>
    </el-drawer>

    <!-- 审核对话框（FR-INV-09） -->
    <el-dialog
      v-model="auditDialog.visible"
      :title="auditDialog.approved ? '审核通过' : '驳回盘点单'"
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
            ? '通过后将按实盘数覆盖库存结存，并生成盘盈/盘亏流水（不可撤销）'
            : '驳回后单据退回盘点中，已录入数据保留供修改后重新提交'
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

  .scope-cell {
    display: flex;
    align-items: center;
    gap: 6px;

    .scope-desc {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .qty {
    font-variant-numeric: tabular-nums;
  }

  .diff-tag {
    margin-left: 6px;
  }

  .surplus {
    color: #67c23a;
    font-weight: 600;
  }

  .shortage {
    color: #f56c6c;
    font-weight: 600;
  }

  .sub {
    font-size: 12px;
    color: #909399;
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
  }

  .option-sub {
    float: right;
    font-size: 12px;
    color: #909399;
  }

  .detail {
    display: flex;
    flex-direction: column;
    min-height: 100%;

    .head {
      margin-bottom: 12px;
    }

    .stats {
      display: flex;
      gap: 12px;
      margin-bottom: 12px;

      .stat {
        flex: 1;
        background: #f5f7fa;
        border-radius: 6px;
        padding: 10px 14px;

        .stat-label {
          font-size: 12px;
          color: #909399;
        }

        .stat-value {
          font-size: 20px;
          font-weight: 700;
          font-variant-numeric: tabular-nums;
          margin-top: 2px;
        }
      }
    }

    .detail-alert {
      margin-bottom: 10px;
    }

    .toolbar {
      display: flex;
      align-items: center;
      gap: 14px;
      margin-bottom: 10px;

      .toolbar-spacer {
        flex: 1;
      }
    }

    .goods-name {
      font-weight: 500;
    }

    .qty-input {
      width: 110px;
    }

    .reason-select {
      width: 130px;
    }
  }

  .drawer-footer {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .footer-tip {
      font-size: 13px;
      color: #e6a23c;
    }
  }
}
</style>

<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm, prompt } from '@/utils/confirm'
import { moneyText } from '@/utils/format'
import { computed, onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { saveBlob } from '@/api/request'
import { getCategoryTree, pageGoods, type CategoryNode, type GoodsListVO } from '@/api/goods'
import { getDictItems } from '@/api/system'
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
  return v == null ? '—' : moneyText(v, 2)
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
  dateRange: null as string[] | null,
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
  loadDiffReasons()
})

// ---------- 差异原因字典（FR-SYS-07 / GAP-P1-09：存字典代码，经任意登录可读接口加载） ----------
/** 差异原因选项：label 展示 / value 存库（代码） */
const diffReasonOptions = ref<{ label: string; value: string }[]>([])
const diffReasonLabels = ref<Record<string, string>>({})

async function loadDiffReasons() {
  try {
    const items = await getDictItems('check_diff_reason')
    diffReasonOptions.value = items.map((i) => ({ label: i.label, value: i.value }))
    diffReasonLabels.value = Object.fromEntries(items.map((i) => [i.value, i.label]))
  } catch {
    // 加载失败时可手工选择，服务端仍会校验字典合法性
  }
}

/** 差异原因显示：字典代码 → 名称；历史数据（存名称）或未知代码原样显示 */
function reasonLabel(value: string | null | undefined) {
  if (!value) return '—'
  return diffReasonLabels.value[value] ?? value
}

// ---------- 新建盘点单（FR-INV-07） ----------
const categoryTree = ref<CategoryNode[]>([])
const createVisible = ref(false)
const creating = ref(false)
const formRef = ref()
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
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
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
      await confirm(
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
/** 审核人与盘点人职责分离（BR-SYS-01，GAP-P1-08）：超管可代审留痕，其余角色由后端按参数拒绝或放行记日志 */
const selfCheck = computed(() => order.value?.checkerName === auth.user?.username)
const isSuperAdmin = computed(() => auth.user?.roleCodes?.includes('SUPER_ADMIN') ?? false)
const auditBlockedBySeparation = computed(() => selfCheck.value && !isSuperAdmin.value)

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
            { label: '差异原因', value: (r) => reasonLabel(r.diffReason) },
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
  confirm('有未保存的实盘数录入，关闭后将丢失，确定关闭？', '未保存提醒', {
    type: 'warning',
  })
    .then(() => done())
    .catch(() => undefined)
}

/** AppDrawer 未提供 before-close：用可写计算属性拦截关闭，复用未保存录入提醒 */
const drawerOpen = computed({
  get: () => drawer.visible,
  set: (visible: boolean) => {
    if (visible) {
      drawer.visible = true
      return
    }
    guardClose(() => {
      drawer.visible = false
    })
  },
})

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
    notify.warning('请先录入实盘数量')
    return
  }
  const noReason = lines.filter(
    (e) => Math.abs(e.actualQty! - e.bookQty) > 1e-9 && !e.diffReason.trim(),
  )
  if (noReason.length) {
    notify.error(`以下商品实盘与账面存在差异，必须填写原因：${noReason.map((e) => e.name).join('、')}`)
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
    notify.success(
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
    notify.warning('请先保存已录入的实盘数')
    return
  }
  await confirm(
    '提交后未录入实盘的行将按账面数量确认，单据进入待审核状态，确定提交？',
    '提交审核',
    { type: 'warning' },
  )
  submitting.value = true
  try {
    const summary = await submitStockCheck(id)
    notify.success(
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
const auditDialog = reactive({ visible: false, approved: true, remark: '', password: '' })
const auditing = ref(false)

function openAudit(approved: boolean) {
  auditDialog.approved = approved
  auditDialog.remark = ''
  auditDialog.password = ''
  auditDialog.visible = true
}

async function saveAudit() {
  const remark = auditDialog.remark.trim()
  if (!auditDialog.approved && !remark) {
    notify.warning('驳回意见不能为空')
    return
  }
  if (!auditDialog.password) {
    notify.warning('请输入当前账号密码以确认操作')
    return
  }
  auditing.value = true
  try {
    await auditStockCheck(drawer.id, auditDialog.approved, remark || undefined, auditDialog.password)
    auditDialog.visible = false
    auditDialog.password = ''
    notify.success(auditDialog.approved ? '审核通过，库存已按实盘数调整' : '已驳回，单据退回盘点中')
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
  const { value } = await prompt(
    '作废后不可恢复（仅盘点中的单据可作废），请填写作废原因：',
    `作废盘点单 ${order.value?.orderNo ?? ''}`,
    {
      type: 'warning',
      inputPlaceholder: '作废原因（必填）',
      inputValidator: (v: string) => !!v?.trim() || '作废原因不能为空',
    },
  )
  await cancelStockCheck(id, value.trim())
  notify.success('已作废')
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
    notify.success(
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
    <UCard class="search-card" :ui="{ body: 'pb-0.5' }">
      <AppForm inline>
        <AppFormItem label="盘点单号">
          <AppInput v-model="query.orderNo" placeholder="单号模糊查询" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="状态">
          <AppSelect v-model="query.status" clearable placeholder="全部" style="width: 110px">
            <AppOption v-for="(s, k) in statusMap" :key="k" :label="s.label" :value="k" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="盘点人">
          <AppInput v-model="query.checkerName" placeholder="用户名" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="审核人">
          <AppInput v-model="query.auditorName" placeholder="用户名" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="盘点日期">
          <AppDatePicker
            v-model="query.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </AppFormItem>
        <AppFormItem>
          <div class="flex items-center gap-2">
            <UButton label="查询" @click="search" />
            <UButton color="neutral" variant="outline" label="重置" @click="resetQuery" />
          </div>
        </AppFormItem>
      </AppForm>
    </UCard>

    <UCard>
      <template #header>
        <div class="card-head">
          <span>盘点单</span>
          <UButton
            v-if="auth.hasPerm('sys:check:create')"
            icon="i-lucide-plus"
            label="新建盘点单"
            @click="openCreate"
          />
        </div>
      </template>

      <AppTable :loading="loading" border :data="list" stripe>
        <AppTableColumn prop="orderNo" label="盘点单号" width="140" />
        <AppTableColumn label="盘点范围" min-width="200">
          <template #default="{ row }">
            <div class="scope-cell">
              <UBadge size="sm" variant="outline">{{ scopeTypeMap[row.scopeType] ?? row.scopeType }}</UBadge>
              <span class="scope-desc">{{ row.scopeDesc }}</span>
            </div>
          </template>
        </AppTableColumn>
        <AppTableColumn label="状态" width="92" align="center">
          <template #default="{ row }">
            <UBadge :color="statusMap[row.status as CheckStatus]?.type ?? 'info'" variant="soft">
              {{ statusMap[row.status as CheckStatus]?.label ?? row.status }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="checkerName" label="盘点人" width="90" />
        <AppTableColumn label="开始时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.startTime) }}</template>
        </AppTableColumn>
        <AppTableColumn label="录入进度" width="150">
          <template #default="{ row }">
            <span class="qty">{{ row.enteredCount }}/{{ row.itemCount }}</span>
            <UBadge v-if="row.diffCount > 0" color="error" variant="soft" size="sm" class="diff-tag">
              差异 {{ row.diffCount }} 行
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="盘盈金额" width="96" align="right">
          <template #default="{ row }">
            <span :class="Number(row.surplusAmount) > 0 ? 'surplus' : ''">{{ money(row.surplusAmount) }}</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="盘亏金额" width="96" align="right">
          <template #default="{ row }">
            <span :class="Number(row.shortageAmount) > 0 ? 'shortage' : ''">{{ money(row.shortageAmount) }}</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="审核人 / 时间" width="170">
          <template #default="{ row }">
            <div v-if="row.auditorName">
              <div>{{ row.auditorName }}</div>
              <div class="sub">{{ fmtTime(row.auditTime) }}</div>
            </div>
            <span v-else>—</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <UButton variant="link" size="sm" @click="openDetail(row as CheckOrderVO)">
              {{ row.status === 'CHECKING' ? '录入' : '详情' }}
            </UButton>
            <UButton
              v-if="row.status === 'CHECKING' && auth.hasPerm('sys:check:submit')"
              variant="link"
              size="sm"
              label="提交审核"
              @click="doSubmit(row.id)"
            />
            <UButton
              v-if="row.status === 'PENDING_AUDIT' && auth.hasPerm('sys:check:audit')"
              variant="link"
              size="sm"
              color="success"
              label="审核"
              @click="openDetail(row as CheckOrderVO)"
            />
            <UButton
              v-if="row.status === 'CHECKING' && auth.hasPerm('sys:check:cancel')"
              variant="link"
              size="sm"
              color="error"
              label="作废"
              @click="doCancel(row.id)"
            />
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

    <!-- 新建盘点单（FR-INV-07） -->
    <AppModal v-model="createVisible" title="新建盘点单" width="560px" :close-on-click-modal="false">
      <UAlert
        color="info"
        variant="soft"
        icon="i-lucide-info"
        title="创建时系统快照账面数量作为盘点基准；同一商品存在未审核完成的盘点单时不可重复创建（BR-INV-09）"
        class="dialog-alert"
      />
      <AppForm ref="formRef" :model="form" :rules="rules" label-width="90px">
        <AppFormItem label="盘点范围" prop="scopeType">
          <AppRadioGroup v-model="form.scopeType">
            <AppRadio value="ALL">全店</AppRadio>
            <AppRadio value="CATEGORY">分类</AppRadio>
            <AppRadio value="GOODS">指定商品</AppRadio>
            <AppRadio value="WARNING">预警商品</AppRadio>
          </AppRadioGroup>
        </AppFormItem>
        <AppFormItem v-if="form.scopeType === 'CATEGORY'" label="盘点分类" prop="categoryId">
          <AppTreeSelect
            v-model="form.categoryId"
            :data="categoryTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            clearable
            placeholder="选择分类（含其子分类）"
            style="width: 100%"
          />
        </AppFormItem>
        <AppFormItem v-if="form.scopeType === 'GOODS'" label="选择商品" prop="goodsIds">
          <AppSelect
            v-model="form.goodsIds"
            multiple
            filterable
            remote
            :remote-method="searchGoods"
            :loading="goodsLoading"
            placeholder="输入名称或条码搜索，最多 500 个"
            style="width: 100%"
          >
            <AppOption
              v-for="g in goodsOptions"
              :key="g.id"
              :label="`${g.name}（${g.goodsCode}）`"
              :value="g.id"
            />
          </AppSelect>
          <div class="form-tip">已选 {{ form.goodsIds.length }} 个商品</div>
        </AppFormItem>
        <AppFormItem v-if="form.scopeType === 'WARNING'" label="范围说明">
          <span class="form-tip">自动纳入缺货 / 预警 / 积压状态的商品</span>
        </AppFormItem>
        <AppFormItem label="备注">
          <AppInput v-model="form.remark" maxlength="100" placeholder="选填，拼入范围描述" />
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="createVisible = false" />
        <UButton :loading="creating" label="创建" @click="saveCreate" />
      </template>
    </AppModal>

    <!-- 详情 / 实盘录入抽屉（FR-INV-08） -->
    <AppDrawer
      v-model="drawerOpen"
      :title="`盘点单 ${order?.orderNo ?? ''}`"
      size="92%"
    >
      <div v-loading="detailLoading" class="detail">
        <!-- 单头信息 -->
        <AppDescriptions :column="4" border size="small" class="head">
          <AppDescriptionsItem label="状态">
            <UBadge :color="statusMap[order?.status as CheckStatus]?.type ?? 'info'" variant="soft" size="sm">
              {{ statusMap[order?.status as CheckStatus]?.label ?? order?.status }}
            </UBadge>
          </AppDescriptionsItem>
          <AppDescriptionsItem label="盘点范围" :span="2">{{ order?.scopeDesc }}</AppDescriptionsItem>
          <AppDescriptionsItem label="盘点人">{{ order?.checkerName }}</AppDescriptionsItem>
          <AppDescriptionsItem label="开始时间">{{ fmtTime(order?.startTime) }}</AppDescriptionsItem>
          <AppDescriptionsItem label="账面基准时间">{{ fmtTime(order?.baseTime) }}</AppDescriptionsItem>
          <AppDescriptionsItem label="审核人">{{ order?.auditorName || '—' }}</AppDescriptionsItem>
          <AppDescriptionsItem label="审核时间">{{ fmtTime(order?.auditTime) }}</AppDescriptionsItem>
          <AppDescriptionsItem v-if="order?.auditRemark" label="审核意见" :span="2">
            {{ order.auditRemark }}
          </AppDescriptionsItem>
          <AppDescriptionsItem v-if="order?.cancelReason" label="作废原因" :span="2">
            {{ order.cancelReason }}
          </AppDescriptionsItem>
        </AppDescriptions>

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
        <UAlert
          v-if="order?.status === 'CHECKING' && order.auditRemark"
          color="warning"
          variant="soft"
          icon="i-lucide-triangle-alert"
          :title="`审核驳回：${order.auditRemark}（单据已退回盘点中，录入数据已保留）`"
          class="detail-alert"
        />
        <UAlert
          v-if="order?.status === 'AUDITED' && (order.duringChangeCount ?? 0) > 0"
          color="info"
          variant="soft"
          icon="i-lucide-info"
          :title="`盘点期间发生 ${order.duringChangeCount} 笔出入库，差异以审核时结存为准（BR-INV-10）`"
          class="detail-alert"
        />
        <UAlert
          v-if="order?.status === 'CHECKING'"
          color="info"
          variant="soft"
          icon="i-lucide-info"
          title="未录入实盘的行提交审核时按账面数量确认；实盘与账面存在差异的行必须填写差异原因"
          class="detail-alert"
        />

        <!-- 明细工具栏 -->
        <div class="toolbar">
          <AppInput
            v-model="itemsQuery.keyword"
            placeholder="扫码 / 编码 / 条码 / 名称定位行"
            clearable
            prefix-icon="i-lucide-search"
            style="width: 240px"
            @keyup.enter="searchItems"
            @clear="searchItems"
          />
          <UCheckbox v-model="itemsQuery.onlyDiff" label="只看差异行" @change="searchItems" />
          <div class="toolbar-spacer" />
          <template v-if="editable">
            <UButton
              icon="i-lucide-download"
              :loading="downloading"
              label="下载模板"
              @click="downloadTemplate"
            />
            <UButton
              icon="i-lucide-upload"
              :loading="importing"
              label="Excel 导入"
              @click="pickImportFile"
            />
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
        <AppTable :data="items" stripe size="small" :empty-text="itemsQuery.onlyDiff ? '没有差异行' : '暂无数据'">
          <AppTableColumn label="商品" min-width="200" fixed="left">
            <template #default="{ row }">
              <div class="goods-name">{{ row.name }}</div>
              <div class="sub">{{ row.goodsCode }} / {{ row.barcode || '无条码' }}<template v-if="row.spec"> / {{ row.spec }}</template></div>
            </template>
          </AppTableColumn>
          <AppTableColumn prop="categoryName" label="分类" width="96" />
          <AppTableColumn prop="unitName" label="单位" width="56" align="center" />
          <AppTableColumn prop="bookQty" label="账面数量" width="92" align="right">
            <template #default="{ row }">{{ qty(row.bookQty) }}</template>
          </AppTableColumn>
          <AppTableColumn label="实盘数量" width="130" align="center">
            <template #default="{ row }">
              <AppInputNumber
                v-if="editable"
                :model-value="displayActual(row as CheckItemVO)"
                :precision="(row as CheckItemVO).isWeighed === 1 ? 3 : 0"
                :step="(row as CheckItemVO).isWeighed === 1 ? 0.001 : 1"
                :min="0"
                :max="99999999.999"
                :controls="false"
                placeholder="未盘"
                class="qty-input"
                @update:model-value="(v: number | null) => setActual(row as CheckItemVO, v)"
              />
              <span v-else>{{ row.actualQty == null ? '未盘' : qty(row.actualQty) }}</span>
            </template>
          </AppTableColumn>
          <AppTableColumn label="差异数量" width="92" align="right">
            <template #default="{ row }">
              <span v-if="calcDiff(row as CheckItemVO) != null" :class="calcDiff(row as CheckItemVO)! > 0 ? 'surplus' : calcDiff(row as CheckItemVO)! < 0 ? 'shortage' : ''">
                {{ calcDiff(row as CheckItemVO)!.toFixed(3).replace(/\.?0+$/, '') || '0' }}
              </span>
              <span v-else>—</span>
            </template>
          </AppTableColumn>
          <AppTableColumn label="差异金额" width="88" align="right">
            <template #default="{ row }">
              <span v-if="calcDiffAmount(row as CheckItemVO) != null" :class="calcDiffAmount(row as CheckItemVO)! > 0 ? 'surplus' : calcDiffAmount(row as CheckItemVO)! < 0 ? 'shortage' : ''">
                {{ money(calcDiffAmount(row as CheckItemVO)) }}
              </span>
              <span v-else>—</span>
            </template>
          </AppTableColumn>
          <AppTableColumn label="差异原因" width="150">
            <template #default="{ row }">
              <AppSelect
                v-if="editable"
                :model-value="displayReason(row as CheckItemVO)"
                filterable
                placeholder="差异≠0 必填"
                size="small"
                class="reason-select"
                @update:model-value="(v: string) => setReason(row as CheckItemVO, v)"
              >
                <AppOption v-for="r in diffReasonOptions" :key="r.value" :label="r.label" :value="r.value" />
              </AppSelect>
              <span v-else>{{ reasonLabel(row.diffReason) }}</span>
            </template>
          </AppTableColumn>
          <AppTableColumn label="备注" width="130">
            <template #default="{ row }">
              <AppInput
                v-if="editable"
                :model-value="displayRemark(row as CheckItemVO)"
                size="small"
                maxlength="100"
                placeholder="选填"
                @update:model-value="(v: string | number | null) => setRemark(row as CheckItemVO, String(v ?? ''))"
              />
              <span v-else>{{ row.remark || '—' }}</span>
            </template>
          </AppTableColumn>
        </AppTable>

        <AppPagination
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
          <div class="footer-actions">
            <UButton color="neutral" variant="outline" :loading="printing" label="打印差异表" @click="printDiff" />
            <UButton v-if="editable" :loading="saving" label="保存录入" @click="saveEntry" />
            <UButton
              v-if="order?.status === 'CHECKING' && auth.hasPerm('sys:check:submit')"
              :loading="submitting"
              color="warning"
              variant="outline"
              label="提交审核"
              @click="doSubmit(drawer.id)"
            />
            <UButton
              v-if="order?.status === 'CHECKING' && auth.hasPerm('sys:check:cancel')"
              color="error"
              variant="outline"
              label="作废"
              @click="doCancel(drawer.id)"
            />
            <UButton
              v-if="order?.status === 'PENDING_AUDIT' && auth.hasPerm('sys:check:audit')"
              color="success"
              label="审核通过"
              @click="openAudit(true)"
            />
            <UButton
              v-if="order?.status === 'PENDING_AUDIT' && auth.hasPerm('sys:check:audit')"
              color="error"
              variant="outline"
              label="驳回"
              @click="openAudit(false)"
            />
            <UBadge v-if="order?.status === 'PENDING_AUDIT' && auditBlockedBySeparation" color="warning" variant="soft">
              本人盘点的单据：按参数职责分离策略处理，可能被拒绝或放行并记异常日志（BR-SYS-01）
            </UBadge>
            <UBadge v-else-if="order?.status === 'PENDING_AUDIT' && selfCheck" color="info" variant="soft">
              超级管理员代审将记录异常日志（BR-SYS-01）
            </UBadge>
          </div>
        </div>
      </template>
    </AppDrawer>

    <!-- 审核对话框（FR-INV-09） -->
    <AppModal
      v-model="auditDialog.visible"
      :title="auditDialog.approved ? '审核通过' : '驳回盘点单'"
      width="480px"
      :close-on-click-modal="false"
    >
      <UAlert
        :color="auditDialog.approved ? 'success' : 'warning'"
        variant="soft"
        :icon="auditDialog.approved ? 'i-lucide-circle-check-big' : 'i-lucide-triangle-alert'"
        class="dialog-alert"
        :title="
          auditDialog.approved
            ? '通过后将按实盘数覆盖库存结存，并生成盘盈/盘亏流水（不可撤销）'
            : '驳回后单据退回盘点中，已录入数据保留供修改后重新提交'
        "
      />
      <AppInput
        v-model="auditDialog.remark"
        type="textarea"
        :rows="3"
        maxlength="200"
        :placeholder="auditDialog.approved ? '审核意见（选填）' : '驳回意见（必填）'"
      />
      <AppInput
        v-model="auditDialog.password"
        type="password"
        show-password
        maxlength="50"
        placeholder="当前账号密码（敏感操作二次确认）"
        class="audit-pwd"
      />
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="auditDialog.visible = false" />
        <UButton
          :color="auditDialog.approved ? 'success' : 'error'"
          :loading="auditing"
          :label="auditDialog.approved ? '确认通过' : '确认驳回'"
          @click="saveAudit"
        />
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
    color: var(--ui-success);
    font-weight: 600;
  }

  .shortage {
    color: var(--ui-error);
    font-weight: 600;
  }

  .sub {
    font-size: 12px;
    color: var(--ui-text-muted);
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }

  .dialog-alert {
    margin-bottom: 14px;
  }

  .audit-pwd {
    margin-top: 12px;
  }

  .form-tip {
    font-size: 12px;
    color: var(--ui-text-muted);
    line-height: 1.6;
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
        background: var(--ui-bg-muted);
        border-radius: 6px;
        padding: 10px 14px;

        .stat-label {
          font-size: 12px;
          color: var(--ui-text-muted);
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
      color: var(--ui-warning);
    }

    .footer-actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: flex-end;
      gap: 8px;
    }
  }
}
</style>

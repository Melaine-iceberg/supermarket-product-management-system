<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm } from '@/utils/confirm'
import { moneyText } from '@/utils/format'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { saveBlob } from '@/api/request'
import { useUnsavedGuard } from '@/utils/unsavedGuard'
import {
  getCategoryTree,
  listBrands,
  listUnits,
  pageGoods,
  updateGoodsStatus,
  deleteGoods,
  exportGoods,
  listGoodsExportFields,
  getGoodsDetail,
  createGoods,
  updateGoods,
  uploadGoodsImage,
  fetchGoodsImageUrl,
  nextBarcode,
  downloadGoodsTemplate,
  validateGoodsImport,
  commitGoodsImport,
  exportGoodsFailedRows,
  createPromotion,
  disablePromotion,
  listPromotionsByGoods,
  getGoodsPriceHistory,
  type CategoryNode,
  type BrandVO,
  type UnitVO,
  type GoodsListVO,
  type GoodsCreatedVO,
  type GoodsImportReportVO,
  type GoodsImportResultVO,
  type GoodsDetailVO,
  type GoodsExportFieldVO,
  type PromotionVO,
  type PriceHistoryVO,
} from '@/api/goods'

const auth = useAuthStore()

/** 商品模块错误码（与后端 ErrorCode 对齐）：UC-GOODS-001 E1/E2 分支交互用 */
const GOODS_CODE_EXISTS = 1301
const GOODS_BARCODE_EXISTS = 1302

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

const stockStatusMap: Record<string, { label: string; type: 'success' | 'warning' | 'error' | 'neutral' }> = {
  NORMAL: { label: '正常', type: 'success' },
  WARNING: { label: '预警', type: 'warning' },
  OUT: { label: '缺货', type: 'error' },
  OVERSTOCK: { label: '积压', type: 'neutral' },
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
const formRef = ref()
const saving = ref(false)
const detailLoading = ref(false)
/** 条码占用高亮与聚焦（UC-GOODS-001 E1） */
const barcodeInvalid = ref(false)
const barcodeInputRef = ref()
const goodsCodeInputRef = ref()

/** AppInput 未暴露 focus()：从组件根节点定位原生 input 聚焦 */
function focusInputField(target: unknown) {
  const root = (target as { $el?: HTMLElement } | null | undefined)?.$el
  root?.querySelector<HTMLInputElement>('input')?.focus()
}

/** 商品图片（IR-SW-05）：存储相对路径 + 本地/受控预览 URL */
const imagePreview = ref('')
const imageUploading = ref(false)
const imageFileInputRef = ref<HTMLInputElement>()

/** 隐藏 file input 触发选择（替代 el-upload 的点击区域） */
function pickImageFile() {
  imageFileInputRef.value?.click()
}

/** 选择文件后本地校验并直接走原自定义上传逻辑 */
async function onImageFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!beforeImageUpload(file)) return
  await doUploadImage({ file })
}

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
  image: '',
})

/** 表单快照：用于未保存离开提醒（IR-UI-07/GAP-P2-25），打开表单时记录、提交成功后无需再比对 */
const formSnapshot = ref('')

/** 是否存在未保存修改（对话框打开且内容与打开时快照不一致） */
function formDirty() {
  return dialogVisible.value && !detailLoading.value && JSON.stringify(form) !== formSnapshot.value
}

useUnsavedGuard(formDirty, '商品表单尚未保存，离开将丢失当前修改，确认离开？')

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
  revokeImagePreview()
  barcodeInvalid.value = false
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
    image: '',
  })
}

/** 释放预览用 blob URL（IR-SW-05），避免对话框反复打开造成内存泄漏 */
function revokeImagePreview() {
  if (imagePreview.value.startsWith('blob:')) {
    URL.revokeObjectURL(imagePreview.value)
  }
  imagePreview.value = ''
}

/** 上传前本地校验（IR-SW-05）：扩展名 + 类型 + ≤2MB，避免无效请求 */
function beforeImageUpload(file: File) {
  const okType = ['image/jpeg', 'image/png'].includes(file.type)
  const okExt = /\.(jpe?g|png)$/i.test(file.name)
  if (!okType || !okExt) {
    notify.error('商品图片仅支持 jpg/png 格式（IR-SW-05）')
    return false
  }
  if (file.size > 2 * 1024 * 1024) {
    notify.error('商品图片大小不能超过 2MB（IR-SW-05）')
    return false
  }
  return true
}

/** 自定义上传：调用受控上传接口，成功后回填相对路径并用本地文件即时预览 */
async function doUploadImage(options: { file: File }) {
  imageUploading.value = true
  try {
    const { path } = await uploadGoodsImage(options.file)
    form.image = path
    revokeImagePreview()
    imagePreview.value = URL.createObjectURL(options.file)
    notify.success('图片已上传，保存商品后生效')
  } catch {
    // 拦截器已提示
  } finally {
    imageUploading.value = false
  }
}

/** 移除图片：清空路径与预览（保存后后端置空） */
function clearImage() {
  form.image = ''
  revokeImagePreview()
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
  formSnapshot.value = JSON.stringify(form)
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
      image: g.image ?? '',
    })
    // 图片为受控接口（需登录态），经 blob 预览；失败不阻断编辑
    if (g.image) {
      const path = g.image
      fetchGoodsImageUrl(path)
        .then((url) => {
          if (form.image === path) {
            imagePreview.value = url
          } else {
            URL.revokeObjectURL(url)
          }
        })
        .catch(() => {})
    }
    dialogVisible.value = true
    formSnapshot.value = JSON.stringify(form)
  } finally {
    detailLoading.value = false
  }
}

/** 店内码预览（FR-GOODS-07）：不落库，仅填充输入框 */
async function fillInStoreBarcode() {
  const { barcode } = await nextBarcode()
  form.barcode = barcode
  barcodeInvalid.value = false
  notify.info(`已生成店内码预览：${barcode}`)
}

/** UC-GOODS-001 E2：清空手工编码，保存时由系统按"分类码+流水"自动生成 */
function regenerateGoodsCode() {
  form.goodsCode = ''
  notify.info('已清空手工编码，保存时按"分类码+流水"自动生成')
  nextTick(() => focusInputField(goodsCodeInputRef.value))
}

function num(v: number | null | undefined) {
  return v == null ? undefined : v
}

async function save() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
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
        await confirm(
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
        image: form.image || undefined,
        priceChangeReason: priceChanged.value ? form.priceChangeReason.trim() || undefined : undefined,
      })
      notify.success('商品已更新')
    } else {
      const created = await createGoods({
        ...base,
        goodsCode: form.goodsCode.trim() || undefined,
        initialStock: num(form.initialStock),
        image: form.image || undefined,
      })
      showCreated(created)
    }
    dialogVisible.value = false
    load()
  } catch (e) {
    // 拦截器已提示；E1/E2 附加字段级交互（UC-GOODS-001）
    const bizCode = (e as { bizCode?: number }).bizCode
    if (bizCode === GOODS_BARCODE_EXISTS) {
      // E1：高亮并聚焦条码字段，提示文案（含占用商品名称）已由拦截器展示
      barcodeInvalid.value = true
      await nextTick()
      focusInputField(barcodeInputRef.value)
    } else if (bizCode === GOODS_CODE_EXISTS && !editing.value) {
      // E2：提供【重新生成编码】入口（清空手工编码 → 保存时自动生成）
      try {
        await confirm(
          '该商品编码已被占用。点击「重新生成编码」将清空手工编码，保存时由系统按分类码+流水自动生成；也可选择「手动修改」自行更换编码。',
          '商品编码重复',
          { confirmButtonText: '重新生成编码', cancelButtonText: '手动修改', type: 'warning' },
        )
        regenerateGoodsCode()
      } catch {
        // 用户选择手动修改
      }
    }
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
  notify.success('已复制到剪贴板')
}

// ---------- 停用 / 启用（FR-GOODS-05） ----------
async function toggleStatus(row: GoodsListVO) {
  const disabling = row.status === 1
  const tip = disabling
    ? `停用后「${row.name}」不可再销售/采购，档案将永久保留，确定停用？`
    : `确定启用商品「${row.name}」？`
  await confirm(tip, disabling ? '停用商品' : '启用商品', { type: 'warning' })
  await updateGoodsStatus(row.id, disabling ? 0 : 1)
  notify.success(disabling ? '已停用' : '已启用')
  load()
}

// ---------- 删除（FR-GOODS-06，仅从未发生业务的商品可删） ----------
async function handleDelete(row: GoodsListVO) {
  await confirm(
    `删除后不可恢复，仅从未发生过业务的商品可删除，确定删除「${row.name}」？`,
    '删除商品',
    { type: 'warning' },
  )
  await deleteGoods(row.id)
  notify.success('已删除')
  load()
}

// ---------- 导出（FR-GOODS-12：与当前筛选一致；字段可选 GAP-P2-07） ----------
const exportDialog = reactive({
  visible: false,
  loadingFields: false,
  exporting: false,
  options: [] as GoodsExportFieldVO[],
  fields: [] as string[],
})

/** 全选（含半选态展示） */
const allExportFields = computed({
  get: () => exportDialog.options.length > 0 && exportDialog.fields.length === exportDialog.options.length,
  set: (v: boolean) => {
    exportDialog.fields = v ? exportDialog.options.map((f) => f.key) : []
  },
})
const exportFieldsIndeterminate = computed(
  () => exportDialog.fields.length > 0 && exportDialog.fields.length < exportDialog.options.length,
)

/** 全选复选框三态（UCheckbox 用 modelValue='indeterminate' 表达半选） */
const allExportFieldsModel = computed(() =>
  exportFieldsIndeterminate.value ? ('indeterminate' as const) : allExportFields.value,
)

function onToggleAllExportFields(value: boolean | 'indeterminate') {
  allExportFields.value = value === true
}

async function openExportDialog() {
  exportDialog.visible = true
  if (exportDialog.options.length === 0) {
    exportDialog.loadingFields = true
    try {
      exportDialog.options = await listGoodsExportFields()
      exportDialog.fields = exportDialog.options.map((f) => f.key)
    } finally {
      exportDialog.loadingFields = false
    }
  }
}

async function confirmExport() {
  if (exportDialog.fields.length === 0) {
    notify.warning('请至少勾选一个导出字段')
    return
  }
  exportDialog.exporting = true
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
      fields: exportDialog.fields.join(','),
    })
    const ts = new Date().toISOString().slice(0, 19).replace(/[-T:]/g, '')
    saveBlob(blob, `商品清单_${ts}.xlsx`)
    notify.success('导出成功')
    exportDialog.visible = false
  } finally {
    exportDialog.exporting = false
  }
}

// ---------- Excel 批量导入（FR-GOODS-11：模板 → 上传校验 → 确认提交 → 失败行导出） ----------
const importDialog = reactive({
  visible: false,
  step: 'upload' as 'upload' | 'report' | 'done',
  file: null as File | null,
  validating: false,
  committing: false,
  /** 当前长任务的文件上传进度（0~100） */
  percent: 0,
  /** 后台导入任务的真实阶段描述（GAP-P2-06：进度条/文案由任务轮询驱动） */
  taskPhase: '',
  report: null as GoodsImportReportVO | null,
  result: null as GoodsImportResultVO | null,
  /** 校验通过后预取的失败行文件：提交后再重新校验会因库内已有数据而失真，故此时取回缓存 */
  failedBlob: null as Blob | null,
  /** 报告展示范围：failed 仅失败行（默认，避免大文件全量渲染）/ all 全部行 */
  scope: 'failed' as 'failed' | 'all',
})

function openImport() {
  Object.assign(importDialog, {
    visible: true,
    step: 'upload',
    file: null,
    validating: false,
    committing: false,
    percent: 0,
    taskPhase: '',
    report: null,
    result: null,
    failedBlob: null,
    scope: 'failed',
  })
}

function onImportFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  importDialog.file = input.files?.[0] ?? null
  input.value = ''
}

const importFileInputRef = ref<HTMLInputElement>()

/** 隐藏 file input 触发选择（替代 el-upload 的拖拽/点击区域） */
function pickImportFile() {
  importFileInputRef.value?.click()
}

function onImportDrop(e: DragEvent) {
  importDialog.file = e.dataTransfer?.files?.[0] ?? null
}

function clearImportFile() {
  importDialog.file = null
  if (importFileInputRef.value) importFileInputRef.value.value = ''
}

function onImportProgress(percent: number) {
  importDialog.percent = percent
}

const importProgressText = computed(() => {
  if (importDialog.percent < 100) return '文件上传中…'
  if (importDialog.committing) {
    return importDialog.taskPhase ? `服务器导入中：${importDialog.taskPhase}` : '服务器导入中，请稍候…'
  }
  return '服务器校验中，请稍候…'
})

async function doValidate() {
  if (!importDialog.file) {
    notify.warning('请先选择导入文件')
    return
  }
  importDialog.validating = true
  importDialog.percent = 0
  try {
    const file = importDialog.file
    importDialog.report = await validateGoodsImport(file, onImportProgress)
    importDialog.step = 'report'
    importDialog.scope = 'failed'
    importDialog.failedBlob = null
    if (importDialog.report.failed > 0) {
      try {
        importDialog.failedBlob = await exportGoodsFailedRows(file)
      } catch {
        // 失败行文件生成失败时导出按钮置灰，不阻断校验流程
      }
    }
  } finally {
    importDialog.validating = false
  }
}

async function doCommit() {
  if (!importDialog.file) return
  importDialog.committing = true
  importDialog.percent = 0
  importDialog.taskPhase = ''
  try {
    const file = importDialog.file
    // 后台任务真实进度（GAP-P2-06）：百分比与阶段同步到对话框进度条
    importDialog.result = await commitGoodsImport(
      file,
      onImportProgress,
      (percent, phase) => {
        importDialog.percent = percent
        importDialog.taskPhase = phase
      },
    )
    importDialog.step = 'done'
    if (importDialog.result.imported > 0) {
      notify.success(`成功导入 ${importDialog.result.imported} 个商品`)
      load()
    }
  } finally {
    importDialog.committing = false
  }
}

async function doDownloadTemplate() {
  saveBlob(await downloadGoodsTemplate(), 'goods-import-template.xlsx')
}

function doExportFailedRows() {
  if (importDialog.failedBlob) {
    saveBlob(importDialog.failedBlob, '商品导入失败行.xlsx')
  }
}

/** 校验报告行：默认仅失败行，可切换全部 */
const reportRows = computed(() => {
  if (!importDialog.report) return []
  return importDialog.scope === 'all'
    ? importDialog.report.rows
    : importDialog.report.rows.filter((r) => !r.passed)
})

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
}

// ---------- 商品详情（FR-GOODS-04：档案 + 库存成本 + 流水/采购价/销量/供应商） ----------

/** 库存流水类型（§8.2 九类变动来源） */
const RECORD_TYPE_MAP: Record<string, string> = {
  INIT: '期初建账',
  PURCHASE_IN: '采购入库',
  PURCHASE_RETURN: '采购退货',
  SALE_OUT: '销售出库',
  SALE_RETURN: '销售退货',
  CHECK_SURPLUS: '盘盈',
  CHECK_SHORTAGE: '盘亏',
  LOSS_OUT: '报损出库',
  OVERFLOW_IN: '报溢入库',
}

/** 价格类型（FR-GOODS-10） */
const PRICE_TYPE_MAP: Record<string, string> = {
  RETAIL: '零售价',
  MEMBER: '会员价',
  PURCHASE: '进货价',
}

const promoStatusMap: Record<string, { label: string; type: 'success' | 'neutral' }> = {
  ACTIVE: { label: '生效中', type: 'success' },
  EXPIRED: { label: '已过期', type: 'neutral' },
  DISABLED: { label: '已停用', type: 'neutral' },
}

const detailDrawer = reactive({
  visible: false,
  loading: false,
  goodsId: 0,
  tab: 'profile' as 'profile' | 'promotion' | 'history',
  data: null as GoodsDetailVO | null,
  promotions: [] as PromotionVO[],
  promotionsLoading: false,
  priceHistory: [] as PriceHistoryVO[],
  historyLoading: false,
})

async function openDetail(row: GoodsListVO) {
  detailDrawer.visible = true
  detailDrawer.goodsId = row.id
  detailDrawer.tab = 'profile'
  detailDrawer.data = null
  detailDrawer.promotions = []
  detailDrawer.priceHistory = []
  await loadDetail()
}

async function loadDetail() {
  detailDrawer.loading = true
  try {
    detailDrawer.data = await getGoodsDetail(detailDrawer.goodsId)
  } finally {
    detailDrawer.loading = false
  }
}

function onDetailTabChange(name: number | string) {
  if (name === 'promotion') loadPromotions()
  else if (name === 'history') loadPriceHistory()
}

async function loadPromotions() {
  detailDrawer.promotionsLoading = true
  try {
    detailDrawer.promotions = await listPromotionsByGoods(detailDrawer.goodsId)
  } finally {
    detailDrawer.promotionsLoading = false
  }
}

async function loadPriceHistory() {
  detailDrawer.historyLoading = true
  try {
    detailDrawer.priceHistory = await getGoodsPriceHistory(detailDrawer.goodsId)
  } finally {
    detailDrawer.historyLoading = false
  }
}

// ---------- 设置促销（FR-GOODS-09：单个/批量，时间区间冲突由后端校验 BR-GOODS-04） ----------

const promoteDialog = reactive({
  visible: false,
  goodsIds: [] as number[],
  goodsNames: [] as string[],
  promoPrice: null as number | null,
  timeRange: null as [string, string] | null,
  remark: '',
  saving: false,
})

/** 表格勾选行（批量设置促销入口） */
const selectedRows = ref<GoodsListVO[]>([])

function onSelectionChange(rows: GoodsListVO[]) {
  selectedRows.value = rows
}

function openPromote(ids: number[], names: string[]) {
  if (!ids.length) {
    notify.warning('请先勾选需要设置促销的商品')
    return
  }
  Object.assign(promoteDialog, {
    visible: true,
    goodsIds: ids,
    goodsNames: names,
    promoPrice: null,
    timeRange: null,
    remark: '',
    saving: false,
  })
}

function openPromoteForSelection() {
  openPromote(
    selectedRows.value.map((r) => r.id),
    selectedRows.value.map((r) => r.name),
  )
}

function openPromoteForDetail() {
  const name = detailDrawer.data?.goods.name ?? ''
  openPromote([detailDrawer.goodsId], [name])
}

async function confirmPromote() {
  if (promoteDialog.promoPrice == null || promoteDialog.promoPrice <= 0) {
    notify.warning('请输入大于 0 的促销价')
    return
  }
  if (!promoteDialog.timeRange || promoteDialog.timeRange.length !== 2) {
    notify.warning('请选择促销生效起止时间')
    return
  }
  promoteDialog.saving = true
  try {
    await createPromotion({
      goodsIds: promoteDialog.goodsIds,
      promoPrice: promoteDialog.promoPrice,
      startTime: promoteDialog.timeRange[0],
      endTime: promoteDialog.timeRange[1],
      remark: promoteDialog.remark.trim() || undefined,
    })
    notify.success(`已为 ${promoteDialog.goodsIds.length} 个商品设置促销`)
    promoteDialog.visible = false
    load()
    if (detailDrawer.visible && promoteDialog.goodsIds.includes(detailDrawer.goodsId)) {
      loadDetail()
      if (detailDrawer.tab === 'promotion') loadPromotions()
    }
  } finally {
    promoteDialog.saving = false
  }
}

async function handleDisablePromotion(promo: PromotionVO) {
  await confirm(
    `停用后促销价 ${money(promo.promoPrice)} 元立即失效，确定停用？`,
    '停用促销',
    { type: 'warning' },
  )
  await disablePromotion(promo.id)
  notify.success('促销已停用')
  loadPromotions()
  loadDetail()
  load()
}
</script>

<template>
  <div class="page">
    <!-- 多维检索（FR-GOODS-03） -->
    <UCard class="search-card" :ui="{ body: 'pb-0.5' }">
      <AppForm inline>
        <AppFormItem label="商品名称">
          <AppInput v-model="query.name" placeholder="名称模糊查询" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="商品编码">
          <AppInput v-model="query.goodsCode" placeholder="编码模糊查询" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="条码">
          <AppInput v-model="query.barcode" placeholder="条码模糊查询" clearable @keyup.enter="search" />
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
        <AppFormItem label="品牌">
          <AppSelect v-model="query.brandId" clearable placeholder="全部" style="width: 140px">
            <AppOption
              v-for="b in brands"
              :key="b.id"
              :label="b.status === 1 ? b.name : `${b.name}（停用）`"
              :value="b.id"
            />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="状态">
          <AppSelect v-model="query.status" clearable placeholder="全部" style="width: 100px">
            <AppOption label="启用" :value="1" />
            <AppOption label="停用" :value="0" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="库存状态">
          <AppSelect v-model="query.stockStatus" clearable placeholder="全部" style="width: 100px">
            <AppOption label="正常" value="NORMAL" />
            <AppOption label="预警" value="WARNING" />
            <AppOption label="缺货" value="OUT" />
            <AppOption label="积压" value="OVERSTOCK" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="称重商品">
          <AppSelect v-model="query.isWeighed" clearable placeholder="全部" style="width: 100px">
            <AppOption label="是" :value="1" />
            <AppOption label="否" :value="0" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="允许退货">
          <AppSelect v-model="query.allowReturn" clearable placeholder="全部" style="width: 100px">
            <AppOption label="允许" :value="1" />
            <AppOption label="不允许" :value="0" />
          </AppSelect>
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
          <span>商品列表</span>
          <div class="flex flex-wrap items-center gap-2">
            <UButton
              v-if="auth.hasPerm('sys:goods:create')"
              icon="i-lucide-plus"
              label="新增商品"
              @click="openCreate"
            />
            <UButton
              v-if="auth.hasPerm('sys:goods:import')"
              icon="i-lucide-upload"
              label="批量导入"
              color="neutral"
              variant="outline"
              @click="openImport"
            />
            <UButton
              v-if="auth.hasPerm('sys:goods:update')"
              icon="i-lucide-tag"
              label="设置促销"
              color="neutral"
              variant="outline"
              @click="openPromoteForSelection"
            />
            <UButton
              v-if="auth.hasPerm('sys:goods:export')"
              icon="i-lucide-download"
              label="导出清单"
              color="neutral"
              variant="outline"
              :loading="exportDialog.exporting"
              @click="openExportDialog"
            />
          </div>
        </div>
      </template>

      <AppTable
        ref="tableRef"
        :loading="loading"
        border
        :data="list"
        stripe
        @sort-change="onSortChange"
        @selection-change="onSelectionChange"
      >
        <AppTableColumn type="selection" width="36" />
        <AppTableColumn prop="goodsCode" label="商品编码" width="130" sortable="custom" />
        <AppTableColumn prop="barcode" label="条码" width="150">
          <template #default="{ row }">{{ row.barcode || '—' }}</template>
        </AppTableColumn>
        <AppTableColumn prop="name" label="商品名称" min-width="170" sortable="custom">
          <template #default="{ row }">
            <div class="goods-name">{{ row.name }}</div>
            <div v-if="row.spec" class="goods-spec">{{ row.spec }}</div>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="categoryName" label="分类" width="110">
          <template #default="{ row }">
            {{ row.categoryName }}
            <UTooltip
              v-if="row.categoryStatus === 0"
              text="所属分类已停用：不可再被新商品选择，存量商品保留（FR-CAT-04）"
              placement="top"
            >
              <UBadge color="error" variant="soft" size="sm" class="cat-disabled-tag">已停用</UBadge>
            </UTooltip>
          </template>
        </AppTableColumn>
        <AppTableColumn label="品牌" width="100">
          <template #default="{ row }">{{ row.brandName || '—' }}</template>
        </AppTableColumn>
        <AppTableColumn prop="unitName" label="单位" width="64" />
        <AppTableColumn label="进货价" width="90" align="right">
          <template #default="{ row }">{{ money(row.purchasePrice) }}</template>
        </AppTableColumn>
        <AppTableColumn prop="retailPrice" label="零售价" width="90" align="right" sortable="custom">
          <template #default="{ row }">{{ money(row.retailPrice) }}</template>
        </AppTableColumn>
        <AppTableColumn label="会员价" width="90" align="right">
          <template #default="{ row }">{{ money(row.memberPrice) }}</template>
        </AppTableColumn>
        <AppTableColumn label="促销价" width="100" align="right">
          <template #default="{ row }">
            <UTooltip
              v-if="row.promoPrice != null"
              :text="`促销截止：${row.promoEndTime}`"
              placement="top"
            >
              <UBadge color="error" variant="soft" size="sm" class="promo-tag">
                {{ money(row.promoPrice) }}
              </UBadge>
            </UTooltip>
            <span v-else>—</span>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="quantity" label="库存" width="110" align="right" sortable="custom">
          <template #default="{ row }">
            <span class="qty" :class="{ 'qty-negative': Number(row.quantity) < 0 }">{{ row.quantity }} {{ row.unitName }}</span>
            <UBadge
              v-if="row.stockStatus !== 'NORMAL'"
              :color="stockStatusMap[row.stockStatus]?.type ?? 'neutral'"
              variant="soft"
              size="sm"
              class="stock-tag"
            >
              {{ stockStatusMap[row.stockStatus]?.label ?? row.stockStatus }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="称重" width="64" align="center">
          <template #default="{ row }">
            <UBadge v-if="row.isWeighed === 1" color="neutral" variant="outline" size="sm">称重</UBadge>
            <span v-else>—</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="状态" width="72" align="center">
          <template #default="{ row }">
            <UBadge :color="row.status === 1 ? 'success' : 'neutral'" variant="soft" size="sm">
              {{ row.status === 1 ? '启用' : '停用' }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="createTime" label="创建时间" width="170" sortable="custom" />
        <AppTableColumn label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <div class="flex items-center gap-1">
              <UButton
                variant="link"
                size="sm"
                label="详情"
                @click="openDetail(row as GoodsListVO)"
              />
              <UButton
                v-if="auth.hasPerm('sys:goods:update')"
                variant="link"
                size="sm"
                label="编辑"
                @click="openEdit(row as GoodsListVO)"
              />
              <UButton
                v-if="auth.hasPerm('sys:goods:status')"
                variant="link"
                size="sm"
                :color="row.status === 1 ? 'error' : 'success'"
                :label="row.status === 1 ? '停用' : '启用'"
                @click="toggleStatus(row as GoodsListVO)"
              />
              <UButton
                v-if="auth.hasPerm('sys:goods:delete')"
                variant="link"
                size="sm"
                color="error"
                label="删除"
                @click="handleDelete(row as GoodsListVO)"
              />
            </div>
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

    <!-- 新增 / 编辑对话框（FR-GOODS-01/02） -->
    <AppModal
      v-model="dialogVisible"
      :title="editing ? `编辑商品：${editing.name}` : '新增商品'"
      width="720px"
      :close-on-click-modal="false"
      @closed="revokeImagePreview"
    >
      <UAlert
        v-if="!editing"
        color="info"
        variant="soft"
        icon="i-lucide-info"
        title="商品编码/条码留空时由系统自动生成；初始库存 >0 将生成「期初建账」库存流水"
        class="dialog-alert"
      />
      <AppForm ref="formRef" v-loading="detailLoading" :model="form" :rules="rules" label-width="96px">
        <AppRow :gutter="12">
          <AppCol :span="24">
            <AppFormItem label="商品图片">
              <div v-loading="imageUploading" class="image-uploader">
                <img v-if="imagePreview" :src="imagePreview" alt="商品图片" class="image-preview" />
                <div v-else class="image-placeholder">暂无图片</div>
                <div class="image-actions">
                  <input
                    ref="imageFileInputRef"
                    type="file"
                    accept=".jpg,.jpeg,.png"
                    hidden
                    @change="onImageFileChange"
                  />
                  <UButton
                    size="sm"
                    color="neutral"
                    variant="outline"
                    :label="form.image ? '更换图片' : '上传图片'"
                    @click="pickImageFile"
                  />
                  <UButton
                    v-if="form.image"
                    size="sm"
                    color="error"
                    variant="ghost"
                    label="移除"
                    @click="clearImage"
                  />
                  <div class="image-tip">支持 jpg/png，单张不超过 2MB（IR-SW-05）</div>
                </div>
              </div>
            </AppFormItem>
          </AppCol>
          <AppCol :span="12">
            <AppFormItem label="商品名称" prop="name">
              <AppInput v-model="form.name" maxlength="100" placeholder="1~100 个字符" />
            </AppFormItem>
          </AppCol>
          <AppCol :span="12">
            <AppFormItem label="所属分类" prop="categoryId">
              <AppTreeSelect
                v-model="form.categoryId"
                :data="categoryOptions"
                :props="{ label: 'name', children: 'children', disabled: 'disabled' }"
                node-key="id"
                :clearable="false"
                placeholder="仅启用的叶子分类可选"
                style="width: 100%"
              />
            </AppFormItem>
          </AppCol>
          <AppCol :span="8">
            <AppFormItem label="计量单位" prop="unitId">
              <AppSelect v-model="form.unitId" placeholder="必选" style="width: 100%">
                <AppOption
                  v-for="u in unitOptions"
                  :key="u.id"
                  :label="`${u.name}（${u.symbol ?? u.name}）`"
                  :value="u.id"
                />
              </AppSelect>
            </AppFormItem>
          </AppCol>
          <AppCol :span="8">
            <AppFormItem label="品牌">
              <AppSelect v-model="form.brandId" clearable placeholder="选填" style="width: 100%">
                <AppOption
                  v-for="b in brands.filter((b) => b.status === 1)"
                  :key="b.id"
                  :label="b.name"
                  :value="b.id"
                />
              </AppSelect>
            </AppFormItem>
          </AppCol>
          <AppCol :span="8">
            <AppFormItem label="称重商品">
              <AppRadioGroup v-model="form.isWeighed">
                <AppRadio :value="1">是</AppRadio>
                <AppRadio :value="0">否</AppRadio>
              </AppRadioGroup>
            </AppFormItem>
          </AppCol>
          <AppCol v-if="form.isWeighed === 1" :span="8">
            <AppFormItem label="PLU 码" prop="pluCode">
              <AppInput v-model="form.pluCode" maxlength="10" placeholder="称重商品必填" />
            </AppFormItem>
          </AppCol>
          <AppCol :span="8">
            <AppFormItem label="规格">
              <AppInput v-model="form.spec" maxlength="50" placeholder="如 330ml" />
            </AppFormItem>
          </AppCol>
          <AppCol :span="8">
            <AppFormItem label="产地">
              <AppInput v-model="form.origin" maxlength="100" placeholder="选填" />
            </AppFormItem>
          </AppCol>
          <AppCol :span="8">
            <AppFormItem label="进货参考价">
              <AppInputNumber
                v-model="form.purchasePrice"
                :min="0"
                :precision="2"
                :controls="false"
                placeholder="≥0"
                style="width: 100%"
              />
            </AppFormItem>
          </AppCol>
          <AppCol :span="8">
            <AppFormItem label="零售单价" prop="retailPrice">
              <AppInputNumber
                v-model="form.retailPrice"
                :min="0.01"
                :precision="2"
                :controls="false"
                placeholder=">0"
                style="width: 100%"
              />
            </AppFormItem>
          </AppCol>
          <AppCol :span="8">
            <AppFormItem label="会员价" prop="memberPrice">
              <AppInputNumber
                v-model="form.memberPrice"
                :min="0"
                :precision="2"
                :controls="false"
                placeholder="≤零售价"
                style="width: 100%"
              />
            </AppFormItem>
          </AppCol>
          <AppCol :span="8">
            <AppFormItem label="安全下限">
              <AppInputNumber
                v-model="form.safetyStockLower"
                :min="0"
                :precision="2"
                :controls="false"
                placeholder="≥0"
                style="width: 100%"
              />
            </AppFormItem>
          </AppCol>
          <AppCol :span="8">
            <AppFormItem label="库存上限" prop="safetyStockUpper">
              <AppInputNumber
                v-model="form.safetyStockUpper"
                :min="0"
                :precision="2"
                :controls="false"
                placeholder="积压预警"
                style="width: 100%"
              />
            </AppFormItem>
          </AppCol>
          <AppCol v-if="!editing" :span="8">
            <AppFormItem label="初始库存">
              <AppInputNumber
                v-model="form.initialStock"
                :min="0"
                :precision="2"
                :controls="false"
                placeholder="留空为 0"
                style="width: 100%"
              />
            </AppFormItem>
          </AppCol>
          <AppCol :span="12">
            <AppFormItem label="商品编码">
              <div class="input-with-append">
                <AppInput
                  ref="goodsCodeInputRef"
                  v-model="form.goodsCode"
                  class="flex-1"
                  :disabled="!!editing"
                  maxlength="20"
                  :placeholder="editing ? '创建后不可修改' : '留空自动生成（分类码+流水）'"
                />
                <UButton
                  v-if="!editing"
                  color="neutral"
                  variant="outline"
                  label="重新生成"
                  @click="regenerateGoodsCode"
                />
              </div>
            </AppFormItem>
          </AppCol>
          <AppCol :span="12">
            <AppFormItem label="商品条码" prop="barcode">
              <div class="input-with-append">
                <AppInput
                  ref="barcodeInputRef"
                  v-model="form.barcode"
                  class="flex-1"
                  maxlength="20"
                  :class="{ 'barcode-error': barcodeInvalid }"
                  placeholder="1~20 位数字"
                  @input="barcodeInvalid = false"
                />
                <UButton
                  v-if="!editing"
                  color="neutral"
                  variant="outline"
                  label="店内码"
                  @click="fillInStoreBarcode"
                />
              </div>
            </AppFormItem>
          </AppCol>
          <AppCol :span="8">
            <AppFormItem label="允许退货">
              <AppRadioGroup v-model="form.allowReturn">
                <AppRadio :value="1">允许</AppRadio>
                <AppRadio :value="0">不允许</AppRadio>
              </AppRadioGroup>
            </AppFormItem>
          </AppCol>
          <AppCol v-if="editing && priceChanged" :span="16">
            <AppFormItem label="调价原因">
              <AppInput
                v-model="form.priceChangeReason"
                maxlength="200"
                placeholder="将随调价历史留痕（FR-GOODS-10），选填"
              />
            </AppFormItem>
          </AppCol>
          <AppCol :span="24">
            <AppFormItem label="备注">
              <AppInput v-model="form.remark" type="textarea" :rows="2" maxlength="500" />
            </AppFormItem>
          </AppCol>
        </AppRow>
      </AppForm>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="dialogVisible = false" />
        <UButton :loading="saving" :disabled="detailLoading" label="保存" @click="save" />
      </template>
    </AppModal>

    <!-- 新增成功：回显生成的编码/店内码 -->
    <AppModal v-model="createdDialog.visible" title="商品创建成功" width="440px">
      <UAlert
        color="success"
        variant="soft"
        icon="i-lucide-circle-check-big"
        :title="`「${form.name}」已创建`"
        class="dialog-alert"
      />
      <div class="created-box">
        <div class="created-row">
          <span class="created-label">商品编码</span>
          <span class="created-value">{{ createdDialog.data?.goodsCode }}</span>
          <UButton
            variant="link"
            size="sm"
            label="复制"
            @click="copyText(createdDialog.data?.goodsCode ?? '')"
          />
        </div>
        <div class="created-row">
          <span class="created-label">商品条码</span>
          <span class="created-value">{{ createdDialog.data?.barcode }}</span>
          <UButton
            variant="link"
            size="sm"
            label="复制"
            @click="copyText(createdDialog.data?.barcode ?? '')"
          />
        </div>
      </div>
    </AppModal>

    <!-- Excel 批量导入（FR-GOODS-11：模板下载 → 上传校验 → 确认提交 → 失败行导出） -->
    <AppModal
      v-model="importDialog.visible"
      title="商品批量导入（Excel）"
      width="760px"
      :close-on-click-modal="false"
    >
      <div v-if="importDialog.validating || importDialog.committing" class="import-progress">
        <UProgress
          :model-value="importDialog.percent"
          :max="100"
          :ui="{ base: 'h-2.5' }"
        />
        <div class="import-progress-text">{{ importProgressText }}</div>
      </div>

      <!-- 第一步：下载模板 + 选择文件 -->
      <template v-if="importDialog.step === 'upload'">
        <UAlert
          color="info"
          variant="soft"
          icon="i-lucide-info"
          title="流程：下载模板 → 填写商品信息 → 上传校验（不写库）→ 确认提交（仅导入校验通过的行）"
          class="dialog-alert"
        />
        <div class="import-actions">
          <UButton
            icon="i-lucide-download"
            color="neutral"
            variant="outline"
            label="下载导入模板"
            @click="doDownloadTemplate"
          />
        </div>
        <div
          class="import-drop"
          role="button"
          tabindex="0"
          @click="pickImportFile"
          @keydown.enter.prevent="pickImportFile"
          @dragover.prevent
          @drop.prevent="onImportDrop"
        >
          <UIcon name="i-lucide-upload" class="size-9 text-dimmed" />
          <div class="import-drop-text">拖拽文件到此处 或 <em>点击选择 .xlsx 文件</em></div>
        </div>
        <input
          ref="importFileInputRef"
          type="file"
          accept=".xlsx"
          hidden
          @change="onImportFileChange"
        />
        <div class="import-tip">单次不超过 5000 行；表头须与标准模板一致，模板示例行会自动跳过</div>
        <div v-if="importDialog.file" class="import-file">
          <UIcon name="i-lucide-file-text" class="size-4 shrink-0 text-dimmed" />
          <span class="import-file-name">{{ importDialog.file.name }}</span>
          <UButton
            size="sm"
            color="neutral"
            variant="ghost"
            label="移除"
            @click="clearImportFile"
          />
        </div>
      </template>

      <!-- 第二步：全量校验报告 -->
      <template v-else-if="importDialog.step === 'report'">
        <UAlert
          :color="importDialog.report!.failed === 0 ? 'success' : 'warning'"
          variant="soft"
          icon="i-lucide-info"
          :title="`共 ${importDialog.report!.total} 行：通过 ${importDialog.report!.passed} 行，失败 ${importDialog.report!.failed} 行`"
          :description="
            importDialog.report!.failed > 0
              ? '确认提交仅导入通过的行；失败行可导出，修正后直接作为导入文件重新上传。'
              : '全部校验通过，可确认提交。'
          "
          class="dialog-alert"
        />
        <AppRadioGroup
          v-if="importDialog.report!.failed > 0"
          v-model="importDialog.scope"
          size="small"
          class="import-scope"
        >
          <AppRadioButton value="failed">仅失败行（{{ importDialog.report!.failed }}）</AppRadioButton>
          <AppRadioButton value="all">全部行（{{ importDialog.report!.total }}）</AppRadioButton>
        </AppRadioGroup>
        <AppTable :data="reportRows" size="small" :max-height="340">
          <AppTableColumn prop="rowNum" label="行号" width="64" align="center" />
          <AppTableColumn label="结果" width="72" align="center">
            <template #default="{ row }">
              <UBadge :color="row.passed ? 'success' : 'error'" variant="soft" size="sm">
                {{ row.passed ? '通过' : '失败' }}
              </UBadge>
            </template>
          </AppTableColumn>
          <AppTableColumn label="商品名称" min-width="140">
            <template #default="{ row }">{{ row.data['商品名称*'] || '—' }}</template>
          </AppTableColumn>
          <AppTableColumn label="商品条码" width="136">
            <template #default="{ row }">{{ row.data['商品条码'] || '—' }}</template>
          </AppTableColumn>
          <AppTableColumn label="失败原因" min-width="230">
            <template #default="{ row }">
              <span v-if="row.errors?.length" class="import-error">{{ row.errors.join('；') }}</span>
              <span v-else>—</span>
            </template>
          </AppTableColumn>
        </AppTable>
      </template>

      <!-- 第三步：提交结果 -->
      <template v-else>
        <div class="import-result">
          <UIcon
            :name="importDialog.result!.failed === 0 ? 'i-lucide-circle-check-big' : 'i-lucide-triangle-alert'"
            class="import-result-icon"
            :class="importDialog.result!.failed === 0 ? 'text-success' : 'text-warning'"
          />
          <div class="import-result-title">
            导入完成：成功 {{ importDialog.result!.imported }} 条，失败 {{ importDialog.result!.failed }} 条
          </div>
          <div v-if="importDialog.result!.failed > 0" class="import-result-sub">
            <span>失败行可点击下方「导出失败行」下载，修正后重新上传（已导入的行不会重复）</span>
          </div>
        </div>
        <AppTable
          v-if="importDialog.result!.failedRows.length"
          :data="importDialog.result!.failedRows"
          size="small"
          :max-height="220"
        >
          <AppTableColumn prop="rowNum" label="行号" width="64" align="center" />
          <AppTableColumn label="商品名称" min-width="140">
            <template #default="{ row }">{{ row.data['商品名称*'] || '—' }}</template>
          </AppTableColumn>
          <AppTableColumn label="失败原因" min-width="280">
            <template #default="{ row }">
              <span class="import-error">{{ row.errors.join('；') }}</span>
            </template>
          </AppTableColumn>
        </AppTable>
      </template>

      <template #footer>
        <!-- 上传步 -->
        <template v-if="importDialog.step === 'upload'">
          <UButton color="neutral" variant="outline" label="取消" @click="importDialog.visible = false" />
          <UButton :loading="importDialog.validating" label="上传校验" @click="doValidate" />
        </template>
        <!-- 校验报告步 -->
        <template v-else-if="importDialog.step === 'report'">
          <UButton
            v-if="importDialog.report!.failed > 0"
            color="neutral"
            variant="outline"
            label="导出失败行"
            :disabled="!importDialog.failedBlob"
            @click="doExportFailedRows"
          />
          <UButton
            color="neutral"
            variant="outline"
            label="重新选择文件"
            @click="importDialog.step = 'upload'"
          />
          <UButton
            :loading="importDialog.committing"
            :disabled="importDialog.report!.passed === 0"
            label="确认提交"
            @click="doCommit"
          />
        </template>
        <!-- 完成步 -->
        <template v-else>
          <UButton
            v-if="importDialog.result!.failedRows.length"
            color="neutral"
            variant="outline"
            label="导出失败行"
            :disabled="!importDialog.failedBlob"
            @click="doExportFailedRows"
          />
          <UButton label="完成" @click="importDialog.visible = false" />
        </template>
      </template>
    </AppModal>

    <!-- 导出字段选择（FR-GOODS-12 / GAP-P2-07） -->
    <AppModal v-model="exportDialog.visible" title="导出商品清单" width="560px">
      <div v-loading="exportDialog.loadingFields">
        <UCheckbox
          :model-value="allExportFieldsModel"
          label="全选"
          @update:model-value="onToggleAllExportFields"
        />
        <USeparator class="export-divider" />
        <UCheckboxGroup
          v-model="exportDialog.fields"
          orientation="horizontal"
          :items="exportDialog.options.map((f) => ({ label: f.label, value: f.key }))"
          class="export-fields"
        />
        <div class="export-tip">
          仅导出勾选字段；导出范围与当前筛选条件一致；行数上限 50000（系统参数 export_max_rows，超限将提示缩小范围）
        </div>
      </div>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="exportDialog.visible = false" />
        <UButton
          :loading="exportDialog.exporting"
          :disabled="exportDialog.fields.length === 0"
          label="导出"
          @click="confirmExport"
        />
      </template>
    </AppModal>

    <!-- 商品详情抽屉（FR-GOODS-04 / FR-GOODS-09 促销记录 / FR-GOODS-10 调价历史） -->
    <AppDrawer
      v-model="detailDrawer.visible"
      :title="`商品详情：${detailDrawer.data?.goods.name ?? ''}`"
      size="62%"
    >
      <div v-loading="detailDrawer.loading" class="detail-body">
        <AppTabs v-model="detailDrawer.tab" @tab-change="onDetailTabChange">
          <AppTabPane label="档案与库存" name="profile">
            <template v-if="detailDrawer.data">
              <UAlert
                v-if="detailDrawer.data.goods.status === 0"
                color="warning"
                variant="soft"
                icon="i-lucide-triangle-alert"
                title="该商品已停用，不可销售/采购（档案保留）"
                class="dialog-alert"
              />
              <AppDescriptions title="商品档案" :column="3" size="small" border class="detail-desc">
                <AppDescriptionsItem label="商品编码">{{ detailDrawer.data.goods.goodsCode }}</AppDescriptionsItem>
                <AppDescriptionsItem label="商品条码">{{ detailDrawer.data.goods.barcode || '—' }}</AppDescriptionsItem>
                <AppDescriptionsItem label="PLU 码">{{ detailDrawer.data.goods.pluCode || '—' }}</AppDescriptionsItem>
                <AppDescriptionsItem label="分类">{{ detailDrawer.data.categoryName || '—' }}</AppDescriptionsItem>
                <AppDescriptionsItem label="品牌">{{ detailDrawer.data.brandName || '—' }}</AppDescriptionsItem>
                <AppDescriptionsItem label="计量单位">{{ detailDrawer.data.unitName || '—' }}</AppDescriptionsItem>
                <AppDescriptionsItem label="规格">{{ detailDrawer.data.goods.spec || '—' }}</AppDescriptionsItem>
                <AppDescriptionsItem label="产地">{{ detailDrawer.data.goods.origin || '—' }}</AppDescriptionsItem>
                <AppDescriptionsItem label="称重商品">{{ detailDrawer.data.goods.isWeighed === 1 ? '是' : '否' }}</AppDescriptionsItem>
                <AppDescriptionsItem label="允许退货">{{ detailDrawer.data.goods.allowReturn === 1 ? '允许' : '不允许' }}</AppDescriptionsItem>
                <AppDescriptionsItem label="状态">
                  <UBadge
                    size="sm"
                    :color="detailDrawer.data.goods.status === 1 ? 'success' : 'neutral'"
                    variant="soft"
                  >
                    {{ detailDrawer.data.goods.status === 1 ? '启用' : '停用' }}
                  </UBadge>
                </AppDescriptionsItem>
                <AppDescriptionsItem label="创建时间">{{ detailDrawer.data.goods.createTime || '—' }}</AppDescriptionsItem>
                <AppDescriptionsItem label="备注" :span="3">{{ detailDrawer.data.goods.remark || '—' }}</AppDescriptionsItem>
              </AppDescriptions>

              <AppDescriptions title="价格与库存" :column="3" size="small" border class="detail-desc">
                <AppDescriptionsItem label="进货参考价">{{ money(detailDrawer.data.goods.purchasePrice) }}</AppDescriptionsItem>
                <AppDescriptionsItem label="零售单价">{{ money(detailDrawer.data.goods.retailPrice) }}</AppDescriptionsItem>
                <AppDescriptionsItem label="会员价">{{ money(detailDrawer.data.goods.memberPrice) }}</AppDescriptionsItem>
                <AppDescriptionsItem label="生效促销">
                  <template v-if="detailDrawer.data.activePromotion">
                    <UBadge color="error" variant="soft" size="sm">{{ money(detailDrawer.data.activePromotion.promoPrice) }} 元</UBadge>
                    <span class="detail-sub">至 {{ detailDrawer.data.activePromotion.endTime }}</span>
                  </template>
                  <span v-else>—</span>
                </AppDescriptionsItem>
                <AppDescriptionsItem label="结存数量">
                  {{ detailDrawer.data.inventory?.quantity ?? 0 }} {{ detailDrawer.data.unitName ?? '' }}
                </AppDescriptionsItem>
                <AppDescriptionsItem label="成本单价">{{ money(detailDrawer.data.inventory?.costPrice) }}</AppDescriptionsItem>
                <AppDescriptionsItem label="成本金额">{{ money(detailDrawer.data.inventory?.costAmount) }}</AppDescriptionsItem>
                <AppDescriptionsItem label="安全库存">
                  {{ detailDrawer.data.goods.safetyStockLower ?? 0 }} ~ {{ detailDrawer.data.goods.safetyStockUpper ?? '不限' }}
                </AppDescriptionsItem>
                <AppDescriptionsItem label="近 30 天销量">{{ detailDrawer.data.salesSummary30d.totalQuantity ?? 0 }}</AppDescriptionsItem>
                <AppDescriptionsItem label="近 30 天销售额">{{ money(detailDrawer.data.salesSummary30d.totalAmount) }}</AppDescriptionsItem>
              </AppDescriptions>

              <div class="detail-section-title">最近库存流水（10 条）</div>
              <AppTable :data="detailDrawer.data.recentInventoryRecords" size="small" :max-height="240">
                <AppTableColumn label="类型" width="90">
                  <template #default="{ row }">{{ RECORD_TYPE_MAP[row.recordType] ?? row.recordType }}</template>
                </AppTableColumn>
                <AppTableColumn label="变动数量" width="90" align="right">
                  <template #default="{ row }">
                    <span :class="row.changeQty >= 0 ? 'qty-in' : 'qty-out'">
                      {{ row.changeQty > 0 ? '+' : '' }}{{ row.changeQty }}
                    </span>
                  </template>
                </AppTableColumn>
                <AppTableColumn prop="beforeQty" label="变动前" width="80" align="right" />
                <AppTableColumn prop="afterQty" label="变动后" width="80" align="right" />
                <AppTableColumn label="关联单据" min-width="150">
                  <template #default="{ row }">{{ row.bizNo || '—' }}</template>
                </AppTableColumn>
                <AppTableColumn label="操作人" width="90">
                  <template #default="{ row }">{{ row.operatorName || '—' }}</template>
                </AppTableColumn>
                <AppTableColumn prop="operateTime" label="时间" width="160" />
              </AppTable>

              <div class="detail-section-title">最近采购价（5 次）</div>
              <AppTable :data="detailDrawer.data.recentPurchasePrices" size="small" :max-height="220">
                <AppTableColumn prop="orderNo" label="入库单号" min-width="170" />
                <AppTableColumn prop="receiptTime" label="入库时间" width="160" />
                <AppTableColumn label="实收单价" width="100" align="right">
                  <template #default="{ row }">{{ money(row.price) }}</template>
                </AppTableColumn>
                <AppTableColumn prop="quantity" label="实收数量" width="100" align="right" />
              </AppTable>

              <div class="detail-section-title">关联供应商</div>
              <AppTable :data="detailDrawer.data.suppliers" size="small" :max-height="220">
                <AppTableColumn prop="supplierName" label="供应商" min-width="150" />
                <AppTableColumn label="供货价" width="100" align="right">
                  <template #default="{ row }">{{ money(row.supplyPrice) }}</template>
                </AppTableColumn>
                <AppTableColumn label="默认供应商" width="100" align="center">
                  <template #default="{ row }">
                    <UBadge v-if="row.isDefault === 1" color="success" variant="soft" size="sm">默认</UBadge>
                    <span v-else>—</span>
                  </template>
                </AppTableColumn>
                <AppTableColumn label="最近供货时间" width="160">
                  <template #default="{ row }">{{ row.lastSupplyTime || '—' }}</template>
                </AppTableColumn>
              </AppTable>
            </template>
          </AppTabPane>

          <AppTabPane label="促销记录" name="promotion">
            <div class="detail-toolbar">
              <UButton
                v-if="auth.hasPerm('sys:goods:update')"
                size="sm"
                label="设置促销"
                @click="openPromoteForDetail"
              />
              <span class="detail-sub">同一商品同一时间段只允许一条生效促销（BR-GOODS-04），到期自动失效</span>
            </div>
            <AppTable :loading="detailDrawer.promotionsLoading" :data="detailDrawer.promotions" size="small">
              <AppTableColumn label="促销价" width="100" align="right">
                <template #default="{ row }">{{ money(row.promoPrice) }}</template>
              </AppTableColumn>
              <AppTableColumn prop="startTime" label="开始时间" width="160" />
              <AppTableColumn prop="endTime" label="结束时间" width="160" />
              <AppTableColumn label="状态" width="90">
                <template #default="{ row }">
                  <UBadge size="sm" :color="promoStatusMap[row.status]?.type ?? 'neutral'" variant="soft">
                    {{ promoStatusMap[row.status]?.label ?? row.status }}
                  </UBadge>
                </template>
              </AppTableColumn>
              <AppTableColumn label="创建人" width="90">
                <template #default="{ row }">{{ row.createBy || '—' }}</template>
              </AppTableColumn>
              <AppTableColumn label="备注" min-width="120">
                <template #default="{ row }">{{ row.remark || '—' }}</template>
              </AppTableColumn>
              <AppTableColumn label="操作" width="80" fixed="right">
                <template #default="{ row }">
                  <UButton
                    v-if="row.status === 'ACTIVE' && auth.hasPerm('sys:goods:update')"
                    variant="link"
                    size="sm"
                    color="error"
                    label="停用"
                    @click="handleDisablePromotion(row as PromotionVO)"
                  />
                </template>
              </AppTableColumn>
            </AppTable>
          </AppTabPane>

          <AppTabPane label="调价历史" name="history">
            <AppTable
              :loading="detailDrawer.historyLoading"
              :data="detailDrawer.priceHistory"
              size="small"
              :max-height="520"
            >
              <AppTableColumn prop="createTime" label="操作时间" width="160" />
              <AppTableColumn label="价格类型" width="90">
                <template #default="{ row }">{{ PRICE_TYPE_MAP[row.priceType] ?? row.priceType }}</template>
              </AppTableColumn>
              <AppTableColumn label="前值" width="100" align="right">
                <template #default="{ row }">{{ money(row.oldValue) }}</template>
              </AppTableColumn>
              <AppTableColumn label="后值" width="100" align="right">
                <template #default="{ row }">
                  <span class="detail-strong">{{ money(row.newValue) }}</span>
                </template>
              </AppTableColumn>
              <AppTableColumn label="生效时间" width="160">
                <template #default="{ row }">{{ row.effectiveTime || '—' }}</template>
              </AppTableColumn>
              <AppTableColumn label="操作人" width="100">
                <template #default="{ row }">{{ row.operatorName || '—' }}</template>
              </AppTableColumn>
              <AppTableColumn label="调价原因" min-width="150">
                <template #default="{ row }">{{ row.reason || '—' }}</template>
              </AppTableColumn>
            </AppTable>
          </AppTabPane>
        </AppTabs>
      </div>
    </AppDrawer>

    <!-- 设置促销对话框（FR-GOODS-09：表格勾选批量 或 详情页单个） -->
    <AppModal
      v-model="promoteDialog.visible"
      title="设置促销价"
      width="520px"
      :close-on-click-modal="false"
    >
      <UAlert
        color="info"
        variant="soft"
        icon="i-lucide-info"
        title="促销价须低于零售价；同一商品同一时间段只允许一条生效促销（BR-GOODS-04）"
        class="dialog-alert"
      />
      <AppForm label-width="90px">
        <AppFormItem label="商品">
          <span>
            {{ promoteDialog.goodsIds.length === 1
              ? promoteDialog.goodsNames[0]
              : `${promoteDialog.goodsNames[0]} 等 ${promoteDialog.goodsIds.length} 个商品` }}
          </span>
        </AppFormItem>
        <AppFormItem label="促销价" required>
          <AppInputNumber
            v-model="promoteDialog.promoPrice"
            :min="0.01"
            :precision="2"
            :controls="false"
            placeholder=">0 且低于零售价"
            style="width: 100%"
          />
        </AppFormItem>
        <AppFormItem label="生效时间" required>
          <AppDatePicker
            v-model="promoteDialog.timeRange"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </AppFormItem>
        <AppFormItem label="备注">
          <AppInput v-model="promoteDialog.remark" maxlength="200" placeholder="选填" />
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="promoteDialog.visible = false" />
        <UButton :loading="promoteDialog.saving" label="确定" @click="confirmPromote" />
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

  .goods-name {
    font-weight: 500;
  }

  .cat-disabled-tag {
    margin-left: 4px;
  }

  .goods-spec {
    font-size: 12px;
    color: var(--el-text-color-secondary);
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

  /* UC-GOODS-001 E1：条码被占用时高亮字段，配合后端"已被商品【XX】占用"提示 */
  .barcode-error {
    box-shadow: 0 0 0 1px var(--ui-error) inset;
    border-radius: 8px;
  }

  /* 商品编码/条码 + 追加按钮（替代 el-input 的 #append 插槽） */
  .input-with-append {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
  }

  .created-box {
    .created-row {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 10px 14px;
      background: var(--el-bg-color-page);
      border-radius: 6px;
      margin-bottom: 8px;

      .created-label {
        color: var(--el-text-color-secondary);
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

  .import-actions {
    display: flex;
    justify-content: center;
    margin-bottom: 14px;
  }

  /* 隐藏 file input + 点击/拖拽区域（替代 el-upload drag） */
  .import-drop {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 6px;
    padding: 22px 16px;
    border: 1px dashed var(--ui-border);
    border-radius: 8px;
    background: var(--ui-bg-muted);
    cursor: pointer;
    transition: border-color 0.2s;

    &:hover {
      border-color: var(--ui-primary);
    }
  }

  .import-drop-text {
    font-size: 14px;
    color: var(--el-text-color-regular);

    em {
      font-style: normal;
      color: var(--ui-primary);
    }
  }

  .import-tip {
    margin-top: 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .import-file {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 10px;
    padding: 6px 10px;
    border-radius: 6px;
    background: var(--ui-bg-muted);
  }

  .import-file-name {
    flex: 1;
    min-width: 0;
    font-size: 13px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  /* 导入结果（替代 el-result） */
  .import-result {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    padding: 10px 0 16px;
    text-align: center;
  }

  .import-result-icon {
    width: 44px;
    height: 44px;
  }

  .import-result-title {
    font-size: 16px;
    font-weight: 600;
  }

  .import-result-sub {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .import-scope {
    margin-bottom: 10px;
  }

  .import-error {
    color: var(--el-color-danger);
    font-size: 12px;
  }

  .import-progress {
    margin-bottom: 12px;

    .import-progress-text {
      margin-top: 4px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }

  .detail-body {
    min-height: 200px;
  }

  .detail-desc {
    margin-bottom: 16px;
  }

  .detail-section-title {
    font-weight: 600;
    font-size: 14px;
    margin: 16px 0 8px;
    padding-left: 8px;
    border-left: 3px solid var(--el-color-primary);
  }

  .detail-toolbar {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 10px;
  }

  .detail-sub {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    margin-left: 6px;
  }

  .detail-strong {
    font-weight: 600;
    color: var(--el-color-danger);
  }

  /* 商品图片上传（IR-SW-05） */
  .image-uploader {
    display: flex;
    align-items: flex-start;
    gap: 12px;
  }

  .image-preview,
  .image-placeholder {
    width: 96px;
    height: 96px;
    border-radius: 6px;
    border: 1px solid var(--el-border-color-lighter);
    flex: none;
  }

  .image-preview {
    display: block;
    object-fit: cover;
  }

  .image-placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--el-text-color-disabled);
    font-size: 12px;
    background: var(--el-fill-color-lighter);
  }

  .image-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
  }

  .image-tip {
    flex-basis: 100%;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  /* 导出字段选择（GAP-P2-07） */
  .export-divider {
    margin: 10px 0;
  }

  .export-fields {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 12px;
  }

  .export-tip {
    margin-top: 12px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .qty-in {
    color: var(--el-color-success);
  }

  /* 负库存高亮（GAP-P2-11） */
  .qty-negative {
    color: var(--el-color-danger);
    font-weight: 600;
  }

  .qty-out {
    color: var(--el-color-danger);
  }
}
</style>

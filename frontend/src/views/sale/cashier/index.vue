<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm, alert } from '@/utils/confirm'
import { moneyText } from '@/utils/format'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { getBusinessParams, getDictItems } from '@/api/system'
import {
  checkout,
  createHold,
  deleteHold,
  getCashierCategoryTree,
  getSalesOrderDetail,
  listHolds,
  pageCashierGoods,
  previewCheckout,
  searchCashierGoods,
  searchMembers,
  takeHold,
  PAY_TYPE_MAP,
  type CashierCategoryNode,
  type CashierGoodsVO,
  type CheckoutPreviewVO,
  type CheckoutVO,
  type HoldVO,
  type MemberSearchVO,
} from '@/api/sale'
import { printSalesReceipt } from '@/utils/receipt'
import { openPrintWindow } from '@/utils/print'
import { newRequestToken } from '@/utils/token'

const auth = useAuthStore()

function money(v: number | null | undefined) {
  return v == null ? '0.00' : moneyText(v, 2)
}

// ---------- 购物车明细（FR-SALE-01/03） ----------
interface CartLine {
  /** 行类型：NORMAL档案商品 / TEMP无码商品（FR-SALE-13） */
  lineType: 'NORMAL' | 'TEMP'
  /** 商品ID（无码商品为空，FR-SALE-13） */
  goodsId: number | null
  name: string
  spec: string | null
  barcode: string | null
  unitName: string
  /** 零售价（展示参考；实际取价由后端按 BR-GOODS-04 解析）；无码商品为直接录入的金额 */
  retailPrice: number
  isWeighed: number
  quantity: number
  remark: string
  /** 店长改价单价（null = 未改价，FR-SALE-07） */
  overridePrice: number | null
  /** 改价原因（改价时必填，FR-SALE-07） */
  priceReason: string
}

const cart = ref<CartLine[]>([])

const itemCount = computed(() =>
  cart.value.reduce((s, l) => s + (l.isWeighed ? 1 : l.quantity), 0),
)

/** 预估商品总额（改价行按改价单价，其余按零售价；促销价/会员价以后端结算为准） */
const estGoodsAmount = computed(() =>
  cart.value.reduce((s, l) => s + (l.overridePrice ?? l.retailPrice) * l.quantity, 0),
)

/** 预估应收 = 零售总额 × 会员折扣率 × 整单折扣率（BR-MEM-04 口径的近似，实际以后端结算为准） */
const estPayable = computed(() => {
  const memberRate = member.value ? Number(member.value.discountRate) : 1
  const orderRate = orderRateInput.value ?? 1
  return Math.round(estGoodsAmount.value * memberRate * orderRate * 100) / 100
})

/** 收款弹窗的后端精确试算结果（含促销价/会员价/等级折扣，避免储值上限用过高的预估应收） */
const payPreview = ref<CheckoutPreviewVO | null>(null)
const previewLoading = ref(false)
/** 试算请求序号：取消后重开弹窗时丢弃旧响应 */
let payPreviewSeq = 0

/** 收款弹窗实际使用的应收：后端试算成功取精确值，失败回退前端预估 */
const payableForPay = computed(() => payPreview.value?.payableAmount ?? estPayable.value)

// ---------- 收银台号（BR-SALE-02 单号组成部分） ----------
const COUNTER_KEY = 'smms_counter_no'
const counterNo = ref<number>(Number(localStorage.getItem(COUNTER_KEY)) || 1)

function saveCounter() {
  if (counterNo.value >= 1 && counterNo.value <= 99) {
    localStorage.setItem(COUNTER_KEY, String(counterNo.value))
  }
}

// ---------- 商品录入（FR-SALE-02：条码/编码/PLU/名称/拼音首字母 + 分类树浏览） ----------
/** 录入框外层容器：AppInput 未暴露内部 input，聚焦与输入法监听经 DOM 获取 */
const inputRef = ref<HTMLElement | null>(null)
const input = ref('')
const searching = ref(false)

/** 原生录入框（AppInput → UInput 内部 input） */
function inputElement(): HTMLInputElement | null {
  return inputRef.value?.querySelector('input') ?? null
}

// ---------- 扫码枪保护（IR-HW-01③④⑤ / GAP-P1-13） ----------
/** 字符间隔 ≤50ms 判定为扫码枪输入（无结束符时据此判定扫码结束并自动查询） */
const SCAN_GAP_MS = 50
/** 快速输入后静默该时长判定扫码结束（无结束符扫码枪兼容） */
const SCAN_IDLE_MS = 120
/** 扫码查询队列：前一次查询未返回时不阻塞后续扫码（IR-HW-01④） */
const scanQueue: string[] = []
let queueRunning = false
let lastKeyTime = 0
let autoQueryTimer: number | undefined
/** 输入法组合中：不触发自动查询（IR-HW-01⑤ 录入框不受输入法状态影响） */
let composing = false

/** 回车：入队并顺序处理（扫描/手工输入等效） */
function onInputEnter() {
  window.clearTimeout(autoQueryTimer)
  enqueueScan(input.value)
}

/** 扫码输入监听：按字符间隔识别扫码枪；静默后自动查询（“扫码后自动查询”参数控制） */
function onScanInput() {
  const now = performance.now()
  const fast = lastKeyTime > 0 && now - lastKeyTime <= SCAN_GAP_MS
  lastKeyTime = now
  if (!fast || composing || !input.value.trim() || bizParams.barcodeAutoQuery === false) return
  window.clearTimeout(autoQueryTimer)
  autoQueryTimer = window.setTimeout(() => {
    if (input.value.trim() && !composing) {
      enqueueScan(input.value)
    }
  }, SCAN_IDLE_MS)
}

/** 立即清空录入框并入队，保证连续快速扫码不丢单 */
function enqueueScan(raw: string) {
  const kw = raw.trim()
  if (!kw) return
  input.value = ''
  scanQueue.push(kw)
  void processQueue()
}

/** 顺序处理队列：同一时刻仅一次查询在途，但输入永不阻塞 */
async function processQueue() {
  if (queueRunning) return
  queueRunning = true
  try {
    while (scanQueue.length) {
      await resolveKeyword(scanQueue.shift() as string)
    }
  } finally {
    queueRunning = false
    focusInput()
  }
}

/** 关键字解析：条码/编码/PLU 唯一精确命中直接入单；否则弹候选列表（名称/拼音模糊） */
async function resolveKeyword(kw: string) {
  searching.value = true
  try {
    const list = await searchCashierGoods(kw)
    const lower = kw.toLowerCase()
    const exact = list.filter(
      (g) =>
        g.barcode === kw ||
        g.goodsCode === kw ||
        (g.pluCode != null && g.pluCode.toLowerCase() === lower),
    )
    if (exact.length === 1 && list.length === 1) {
      addToCart(exact[0])
      return
    }
    candidates.value = list
    candidateVisible.value = list.length > 0
    if (list.length === 0) {
      notify.warning(`未找到匹配【${kw}】的商品`)
    }
  } finally {
    searching.value = false
  }
}

/** 候选列表选择入单 */
const candidates = ref<CashierGoodsVO[]>([])
const candidateVisible = ref(false)

function pickCandidate(goods: CashierGoodsVO) {
  candidateVisible.value = false
  addToCart(goods)
  input.value = ''
}

/** 入单：称重商品弹净重输入（FR-SALE-04）；同商品累加数量（BR-SALE-01） */
const weightDialog = reactive({
  visible: false,
  saving: false,
  goods: null as CashierGoodsVO | null,
  netWeight: null as number | null,
})

function addToCart(goods: CashierGoodsVO) {
  if (goods.isWeighed === 1) {
    weightDialog.goods = goods
    weightDialog.netWeight = null
    weightDialog.visible = true
    return
  }
  doAdd(goods, 1)
}

function confirmWeight() {
  const w = weightDialog.netWeight
  if (w == null || w <= 0) {
    notify.warning('净重必须大于 0（FR-SALE-04）')
    return
  }
  weightDialog.visible = false
  doAdd(weightDialog.goods!, Math.round(w * 1000) / 1000)
}

function doAdd(goods: CashierGoodsVO, quantity: number) {
  const exist = cart.value.find((l) => l.goodsId === goods.id)
  if (exist) {
    // 同商品重复录入累加数量而非新增行（BR-SALE-01）
    exist.quantity = Math.round((exist.quantity + quantity) * 1000) / 1000
  } else {
    cart.value.push({
      lineType: 'NORMAL',
      goodsId: goods.id,
      name: goods.name,
      spec: goods.spec,
      barcode: goods.barcode,
      unitName: goods.unitName,
      retailPrice: Number(goods.retailPrice),
      isWeighed: goods.isWeighed,
      quantity,
      remark: '',
      overridePrice: null,
      priceReason: '',
    })
  }
}

// ---------- 分类树浏览点选（FR-SALE-02③，GAP-P0-06：收银专用只读接口，sys:cashier:enter 鉴权） ----------
const browseDialog = reactive({
  visible: false,
  loading: false,
  /** 当前选中分类（含子分类，null = 全部） */
  categoryId: null as number | null,
  categoryTree: [] as CashierCategoryNode[],
  goods: [] as CashierGoodsVO[],
})

function openBrowse() {
  browseDialog.visible = true
  browseDialog.categoryId = null
  if (browseDialog.categoryTree.length === 0) {
    getCashierCategoryTree().then((data) => (browseDialog.categoryTree = data))
  }
  loadBrowseGoods()
}

/** 加载分类（含子分类）下启用商品，最多 100 条 */
async function loadBrowseGoods() {
  browseDialog.loading = true
  try {
    const data = await pageCashierGoods({
      categoryId: browseDialog.categoryId ?? undefined,
      page: 1,
      size: 100,
    })
    browseDialog.goods = data.list
  } finally {
    browseDialog.loading = false
  }
}

function pickBrowseCategory(node: CashierCategoryNode) {
  browseDialog.categoryId = node.id
  loadBrowseGoods()
}

function pickBrowseGoods(goods: CashierGoodsVO) {
  addToCart(goods)
  if (goods.isWeighed !== 1) {
    notify.success(`已加入：${goods.name}`)
  }
}

/** 分类树平铺（整树展开展示；替代 el-tree 的 default-expand-all 展示方式） */
const browseCategoryRows = computed(() => {
  const rows: { node: CashierCategoryNode; depth: number }[] = []
  const walk = (nodes: CashierCategoryNode[], depth: number) => {
    for (const node of nodes) {
      rows.push({ node, depth })
      if (node.children?.length) walk(node.children, depth + 1)
    }
  }
  walk(browseDialog.categoryTree, 0)
  return rows
})

// ---------- 无码商品临时登记（FR-SALE-13，Could） ----------
const tempDialog = reactive({
  visible: false,
  name: '',
  amount: null as number | null,
})

function openTempDialog() {
  tempDialog.name = ''
  tempDialog.amount = null
  tempDialog.visible = true
}

/** 录入名称与金额直接计入本单（不关联 SKU、不扣库存、不支持退货） */
function confirmTempItem() {
  const name = tempDialog.name.trim()
  const amount = tempDialog.amount
  if (!name) {
    notify.warning('请输入无码商品名称')
    return
  }
  if (name.length > 100) {
    notify.warning('名称最多 100 字符')
    return
  }
  if (amount == null || amount <= 0) {
    notify.warning('金额必须大于 0')
    return
  }
  cart.value.push({
    lineType: 'TEMP',
    goodsId: null,
    name,
    spec: null,
    barcode: null,
    unitName: '项',
    retailPrice: Math.round(amount * 100) / 100,
    isWeighed: 0,
    quantity: 1,
    remark: '',
    overridePrice: null,
    priceReason: '',
  })
  tempDialog.visible = false
  focusInput()
}

/** 数量修改（普通商品正整数、称重 3 位小数，FR-SALE-03） */
function onQtyChange(line: CartLine) {
  if (line.quantity <= 0) line.quantity = 1
  if (line.isWeighed === 1) {
    line.quantity = Math.round(line.quantity * 1000) / 1000
    if (line.quantity <= 0) removeLine(line)
  } else {
    line.quantity = Math.floor(line.quantity) || 1
  }
}

function removeLine(line: CartLine) {
  // 按行对象删除（无码商品 goodsId 为空，不能按 goodsId 过滤）
  cart.value = cart.value.filter((l) => l !== line)
}

async function clearCart() {
  if (cart.value.length === 0) return
  await confirm('确认清空当前全部明细？', '整单清空', { type: 'warning' })
  resetOrder()
}

/** 重置单据（不含收银台号） */
function resetOrder() {
  cart.value = []
  member.value = null
  memberDisabledTip.value = false
  orderRateInput.value = null
  discountReasonApplied.value = ''
  orderAuth.username = ''
  orderAuth.password = ''
  orderRateDialog.authorizerUsername = ''
  orderRateDialog.authorizerPassword = ''
  overrideDialog.visible = false
  remark.value = ''
}

const remark = ref('')

// ---------- 会员识别（FR-SALE-05） ----------
const member = ref<MemberSearchVO | null>(null)
/** 停用会员常驻提示（BR-MEM-03/GAP-P2-02：提示且不给折扣，按散客继续结算） */
const memberDisabledTip = ref(false)
const memberDialog = reactive({ visible: false, keyword: '', loading: false })
const memberCandidates = ref<MemberSearchVO[]>([])

function openMemberDialog() {
  memberDialog.keyword = ''
  memberCandidates.value = []
  memberDialog.visible = true
}

async function doSearchMembers() {
  const kw = memberDialog.keyword.trim()
  if (!kw) return
  memberDialog.loading = true
  try {
    memberCandidates.value = await searchMembers(kw)
    if (memberCandidates.value.length === 0) {
      notify.warning('未找到匹配会员')
    }
  } finally {
    memberDialog.loading = false
  }
}

function pickMember(m: MemberSearchVO) {
  member.value = m
  memberDialog.visible = false
  // 停用会员不阻断结算（BR-MEM-03/GAP-P2-02）：常驻提示、不享受折扣与积分，按散客继续
  memberDisabledTip.value = m.status !== 1
}

function clearMember() {
  member.value = null
  memberDisabledTip.value = false
}

// ---------- 整单折扣与单品改价（FR-SALE-07，店长现场授权） ----------
/** 业务参数（BR-SALE-05/10：与后端校验同源，避免硬编码；读取失败按默认值展示） */
const bizParams = reactive({ minDiscountRate: 0.7, barcodeAutoQuery: true })

async function loadBizParams() {
  try {
    Object.assign(bizParams, await getBusinessParams())
  } catch {
    // 读取失败按默认值展示，后端结算时仍会校验
  }
}

// ---------- 收款方式（FR-SYS-07：选项来自数据字典 pay_type 启用项，停用项不可选） ----------
/** 收款方式选项：字典读取失败/为空时回退内置展示 */
const payTypeOptions = ref<{ label: string; value: string }[]>(
  Object.entries(PAY_TYPE_MAP).map(([value, label]) => ({ label, value })),
)

async function loadPayTypes() {
  try {
    const items = await getDictItems('pay_type')
    if (items.length) {
      payTypeOptions.value = items.map((i) => ({ label: i.label, value: i.value }))
    }
  } catch {
    // 回退内置选项，后端结算时仍会校验
  }
}

const orderRateInput = ref<number | null>(null)
/** 整单折扣原因（已生效值，FR-SALE-07 必填） */
const discountReasonApplied = ref('')

/** 本单店长授权信息（整单折扣/单品改价共用，结算时提交核验；密码不落挂单、不持久化） */
const orderAuth = reactive({ username: '', password: '' })

const orderRateDialog = reactive({
  visible: false,
  rate: null as number | null,
  discountReason: '',
  authorizerUsername: '',
  authorizerPassword: '',
})

function openOrderRateDialog() {
  orderRateDialog.rate = orderRateInput.value
  orderRateDialog.discountReason = discountReasonApplied.value
  orderRateDialog.authorizerUsername = orderAuth.username
  orderRateDialog.authorizerPassword = ''
  orderRateDialog.visible = true
}

function confirmOrderRate() {
  const rate = orderRateDialog.rate
  if (rate == null || rate < bizParams.minDiscountRate || rate > 1) {
    notify.warning(`整单折扣率须在 ${bizParams.minDiscountRate}~1 之间（BR-SALE-10）`)
    return
  }
  // 最终折扣率 = 会员等级折扣 × 整单折扣，不得低于下限（BR-SALE-10，GAP-P0-09 后端强校验）
  const memberRate = member.value ? Number(member.value.discountRate) : 1
  const finalRate = Math.round(rate * memberRate * 10000) / 10000
  if (finalRate < bizParams.minDiscountRate) {
    notify.warning(
      `会员折扣 ${memberRate} × 整单折扣 ${rate} = 最终折扣率 ${finalRate}，低于下限 ${bizParams.minDiscountRate}（BR-SALE-10），请调整`,
    )
    return
  }
  if (rate < 1) {
    if (!orderRateDialog.discountReason.trim()) {
      notify.warning('整单折扣须填写原因（FR-SALE-07）')
      return
    }
    if (!orderRateDialog.authorizerUsername.trim()) {
      notify.warning('整单折扣须店长授权，请填写授权店长账号（FR-SALE-07）')
      return
    }
    if (orderRateDialog.authorizerUsername.trim() === auth.user?.username) {
      notify.warning('折扣授权人不得为当前收银员本人（FR-SALE-07）')
      return
    }
    if (!orderRateDialog.authorizerPassword) {
      notify.warning('请填写授权店长密码以完成授权（FR-SALE-07）')
      return
    }
    orderAuth.username = orderRateDialog.authorizerUsername.trim()
    orderAuth.password = orderRateDialog.authorizerPassword
    discountReasonApplied.value = orderRateDialog.discountReason.trim()
  } else {
    discountReasonApplied.value = ''
  }
  orderRateInput.value = rate
  orderRateDialog.visible = false
}

function clearOrderRate() {
  orderRateInput.value = null
  discountReasonApplied.value = ''
}

/** 单品改价（FR-SALE-07）：店长现场输入账号密码授权 + 改价原因，仅档案商品 */
const overrideDialog = reactive({
  visible: false,
  line: null as CartLine | null,
  price: null as number | null,
  reason: '',
  authorizerUsername: '',
  authorizerPassword: '',
})

function openOverrideDialog(line: CartLine) {
  overrideDialog.line = line
  overrideDialog.price = line.overridePrice
  overrideDialog.reason = line.priceReason
  overrideDialog.authorizerUsername = orderAuth.username
  overrideDialog.authorizerPassword = ''
  overrideDialog.visible = true
}

function confirmOverride() {
  const price = overrideDialog.price
  if (price == null || price <= 0) {
    notify.warning('改价单价必须大于 0（FR-SALE-07）')
    return
  }
  const reason = overrideDialog.reason.trim()
  if (!reason) {
    notify.warning('单品改价须填写原因（FR-SALE-07）')
    return
  }
  if (reason.length > 200) {
    notify.warning('改价原因最多 200 字符')
    return
  }
  if (!overrideDialog.authorizerUsername.trim()) {
    notify.warning('单品改价须店长授权，请填写授权店长账号（FR-SALE-07）')
    return
  }
  if (overrideDialog.authorizerUsername.trim() === auth.user?.username) {
    notify.warning('改价授权人不得为当前收银员本人（FR-SALE-07）')
    return
  }
  if (!overrideDialog.authorizerPassword) {
    notify.warning('请填写授权店长密码以完成授权（FR-SALE-07）')
    return
  }
  const line = overrideDialog.line!
  line.overridePrice = Math.round(price * 100) / 100
  line.priceReason = reason
  orderAuth.username = overrideDialog.authorizerUsername.trim()
  orderAuth.password = overrideDialog.authorizerPassword
  overrideDialog.visible = false
}

/** 取消已生效的改价（恢复按取价规则成交） */
function cancelOverride() {
  if (overrideDialog.line) {
    overrideDialog.line.overridePrice = null
    overrideDialog.line.priceReason = ''
  }
  overrideDialog.visible = false
}

const orderRate = computed(() =>
  orderRateInput.value != null && orderRateInput.value < 1 ? orderRateInput.value : undefined,
)

/** 本单是否存在改价行 */
const hasOverride = computed(() => cart.value.some((l) => l.overridePrice != null))

/** 本单是否需要店长授权（打折或改价任一即需要） */
const needsAuthorizer = computed(() => orderRate.value != null || hasOverride.value)

// ---------- 结算（FR-SALE-06，F2） ----------
const payDialog = reactive({
  visible: false,
  submitting: false,
  requestToken: '',
  lines: [] as { payType: string; amount: number | null }[],
})

const paidTotal = computed(() =>
  payDialog.lines.reduce((s, l) => s + (l.amount ?? 0), 0),
)

const estChange = computed(() =>
  Math.round((paidTotal.value - payableForPay.value) * 100) / 100,
)

/** 储值可用上限 = min(应收, 会员余额)（BR-MEM-07：储值不参与找零、余额不可为负） */
const balanceCap = computed(() => {
  if (!member.value) return 0
  return Math.round(Math.min(payableForPay.value, Number(member.value.balance)) * 100) / 100
})

/** 结算/试算共用的明细入参：单价由后端按 BR-GOODS-04 解析，前端不传价 */
function buildCheckoutItems() {
  return cart.value.map((l) =>
    l.lineType === 'TEMP'
      ? {
          tempName: l.name,
          tempAmount: l.retailPrice,
          quantity: 1,
          remark: l.remark || undefined,
        }
      : {
          goodsId: l.goodsId!,
          quantity: l.quantity,
          netWeight: l.isWeighed === 1 ? l.quantity : undefined,
          remark: l.remark || undefined,
          overridePrice: l.overridePrice ?? undefined,
          priceReason: l.overridePrice != null ? l.priceReason || undefined : undefined,
        },
  )
}

async function openPayDialog() {
  if (cart.value.length === 0) {
    notify.warning('销售明细为空')
    return
  }
  if (counterNo.value < 1 || counterNo.value > 99) {
    notify.warning('请先设置收银台号（1~99）')
    return
  }
  payDialog.lines = [{ payType: 'CASH', amount: null }]
  // 每次进入结算生成请求令牌（NFR-REL-02 防重）：失败重试复用同令牌，成功后再开新单换新令牌
  payDialog.requestToken = newRequestToken()
  payPreview.value = null
  payDialog.visible = true
  // 打开弹窗即向后端试算精确应收（促销价/会员价/等级折扣与结算完全同口径），
  // 避免前端按零售价预估导致储值金额超过后端应收而被拒（BR-MEM-07）
  const seq = ++payPreviewSeq
  previewLoading.value = true
  try {
    const preview = await previewCheckout({
      memberId: member.value && member.value.status === 1 ? member.value.id : undefined,
      orderDiscountRate: orderRate.value,
      items: buildCheckoutItems(),
    })
    if (seq === payPreviewSeq) payPreview.value = preview
  } catch {
    // 拦截器已提示；保留前端预估金额，结算时后端仍会兜底校验
  } finally {
    if (seq === payPreviewSeq) previewLoading.value = false
  }
}

/** 关闭收款弹窗后丢弃试算结果，避免主界面“收款不足”沿用弹窗精确值 */
watch(
  () => payDialog.visible,
  (visible) => {
    if (!visible) {
      payPreviewSeq++
      payPreview.value = null
      previewLoading.value = false
    }
  },
)

function addPayLine() {
  payDialog.lines.push({ payType: 'MOBILE', amount: null })
}

function removePayLine(idx: number) {
  if (payDialog.lines.length <= 1) return
  payDialog.lines.splice(idx, 1)
}

/** 快捷填入：现金金额 = 应收 */
function fillExact() {
  payDialog.lines.forEach((l) => (l.amount = null))
  payDialog.lines[0].amount = payableForPay.value
}

/** 储值快捷：BALANCE 行金额 = min(应收, 余额)；无 BALANCE 行则追加（FR-MEM-08） */
function fillBalance() {
  if (!member.value) {
    notify.warning('请先识别会员（储值支付仅限会员）')
    return
  }
  let line = payDialog.lines.find((l) => l.payType === 'BALANCE')
  if (!line) {
    line = { payType: 'BALANCE', amount: null }
    payDialog.lines.push(line)
  }
  line.amount = balanceCap.value
}

/** 现金快捷：实收额（如 100/50 整钞），找零自动计算 */
function fillCash(amount: number) {
  const cashLine = payDialog.lines.find((l) => l.payType === 'CASH')
  if (cashLine) cashLine.amount = amount
}

const CASH_QUICK = [10, 20, 50, 100]

async function submitCheckout() {
  const pays = payDialog.lines.filter((l) => l.amount != null && l.amount > 0)
  if (pays.length === 0) {
    notify.warning('请录入收款金额')
    return
  }
  // 储值支付前端预检（后端兜底）：仅限会员、合计不超应收（不参与找零）与余额（BR-MEM-07）
  const balancePaid = pays
    .filter((l) => l.payType === 'BALANCE')
    .reduce((s, l) => s + (l.amount ?? 0), 0)
  if (balancePaid > 0) {
    if (!member.value) {
      notify.warning('储值支付仅限会员使用（FR-MEM-08）')
      return
    }
    if (balancePaid > balanceCap.value + 0.005) {
      notify.warning(`储值支付不得超过 ￥${money(balanceCap.value)}（应收/余额限制，BR-MEM-07）`)
      return
    }
  }
  const paid = pays.reduce((s, l) => s + (l.amount ?? 0), 0)
  if (paid < payableForPay.value - 0.005) {
    notify.warning(`实收 ${money(paid)} 元不足应收 ${money(payableForPay.value)} 元`)
    return
  }
  payDialog.submitting = true
  try {
    // 折扣/改价已配置但授权密码缺失（如取单后未重新录入），退回对应对话框补充授权
    if (needsAuthorizer.value && !orderAuth.password) {
      if (orderRateInput.value != null) {
        openOrderRateDialog()
      } else {
        const line = cart.value.find((l) => l.overridePrice != null)
        if (line) openOverrideDialog(line)
      }
      notify.warning('折扣/改价需店长授权，请重新填写授权密码（FR-SALE-07）')
      return
    }
    const result = await checkout({
      counterNo: counterNo.value,
      // 停用会员不享受折扣与积分（BR-MEM-03），按散客提交；后端亦兜底忽略停用会员
      memberId: member.value && member.value.status === 1 ? member.value.id : undefined,
      orderDiscountRate: orderRate.value,
      authorizerUsername: needsAuthorizer.value ? orderAuth.username : undefined,
      authorizerPassword: needsAuthorizer.value ? orderAuth.password : undefined,
      discountReason: orderRate.value != null ? discountReasonApplied.value || undefined : undefined,
      remark: remark.value || undefined,
      items: buildCheckoutItems(),
      payments: pays.map((l) => ({ payType: l.payType, amount: l.amount! })),
      requestToken: payDialog.requestToken || undefined,
    })
    payDialog.visible = false
    doneDialog.result = result
    doneDialog.visible = true
  } catch (e) {
    // 拦截器已提示；库存不足/商品不可售等须在显著位置常驻提示并手动确认（IR-UI-06）
    showPersistentCheckoutError(e)
  } finally {
    payDialog.submitting = false
  }
}

/** 需收银员手动确认的结算异常码：库存不足 1401、商品不存在或已停用 1501（IR-UI-06） */
const PERSISTENT_ERROR_CODES = new Set([1401, 1501])

/** 异常常驻提示：不自动消失，需收银员点击确认（IR-UI-06）；其余错误沿用拦截器轻提示 */
function showPersistentCheckoutError(e: unknown) {
  const err = e as { response?: { data?: { code?: number; message?: string } } }
  const code = err?.response?.data?.code
  const message = err?.response?.data?.message
  if (code == null || !PERSISTENT_ERROR_CODES.has(code)) return
  alert(message || '结算失败，请核对商品与库存后重试', '结算异常', {
    type: 'error',
    confirmButtonText: '我已确认',
    closeOnClickModal: false,
    closeOnPressEscape: false,
  }).catch(() => undefined)
}

const doneDialog = reactive({ visible: false, result: null as CheckoutVO | null })

function closeDone() {
  doneDialog.visible = false
  resetOrder()
  focusInput()
}

/** 打印小票（FR-RPT-09）：明细取后端成交快照，不用购物车数据（BR-SALE-04） */
function printDoneReceipt() {
  const orderId = doneDialog.result?.orderId
  if (!orderId) return
  const win = openPrintWindow('receipt')
  if (!win) return
  getSalesOrderDetail(orderId).then((d) => printSalesReceipt(d, win))
}

// ---------- 挂单与取单（FR-SALE-08：落库 hold_order，不扣库存不计报表，超 24 小时自动清理） ----------
const holdDialog = reactive({ visible: false, holds: [] as HoldVO[] })

/** 当前挂单数（角标展示），挂单/取单/删除/进入页面时刷新 */
const holdCount = ref(0)

async function refreshHoldCount() {
  try {
    holdCount.value = (await listHolds()).length
  } catch {
    // 拦截器已提示；角标保持原值
  }
}

/** 挂单时间展示（后端 ISO 时间） */
function fmtHoldTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 16) : '—'
}

async function holdOrder() {
  if (cart.value.length === 0) {
    notify.warning('销售明细为空，无需挂单')
    return
  }
  try {
    await createHold({
      counterNo: counterNo.value,
      memberId: member.value?.id,
      memberName: member.value?.name,
      orderRate: orderRateInput.value ?? undefined,
      discountReason: discountReasonApplied.value || undefined,
      authorizerUsername: orderAuth.username || undefined,
      remark: remark.value.trim() || undefined,
      items: cart.value.map((l) => ({
        lineType: l.lineType,
        goodsId: l.goodsId ?? undefined,
        name: l.name,
        spec: l.spec,
        barcode: l.barcode,
        unitName: l.unitName,
        retailPrice: l.retailPrice,
        isWeighed: l.isWeighed,
        quantity: l.quantity,
        remark: l.remark || undefined,
        overridePrice: l.overridePrice ?? undefined,
        priceReason: l.overridePrice != null ? l.priceReason || undefined : undefined,
      })),
    })
    resetOrder()
    refreshHoldCount()
    notify.success('已挂单（挂单不扣库存，交班前请及时取单结算）')
    focusInput()
  } catch {
    // 拦截器已提示
  }
}

async function openHoldDialog() {
  holdDialog.holds = await listHolds().catch(() => holdDialog.holds)
  holdDialog.visible = true
}

/** 取单：先由后端条件更新占用（ACTIVE→TAKEN，防并发重复取单），再恢复购物车 */
async function resumeHold(hold: HoldVO) {
  if (cart.value.length > 0) {
    notify.warning('当前已有明细，请先结算或清空再取单')
    return
  }
  const detail = await takeHold(hold.id).catch(() => null)
  if (!detail) {
    // 已被取单/删除：刷新列表与角标
    holdDialog.holds = await listHolds().catch(() => holdDialog.holds)
    refreshHoldCount()
    return
  }
  cart.value = detail.items.map((l) => ({
    lineType: l.lineType,
    goodsId: l.goodsId ?? null,
    name: l.name,
    spec: l.spec ?? null,
    barcode: l.barcode ?? null,
    unitName: l.unitName,
    retailPrice: Number(l.retailPrice),
    isWeighed: l.isWeighed,
    quantity: Number(l.quantity),
    remark: l.remark ?? '',
    overridePrice: l.overridePrice ?? null,
    priceReason: l.priceReason ?? '',
  }))
  if (detail.memberId) {
    member.value = {
      id: detail.memberId,
      memberNo: '',
      name: detail.memberName ?? '',
      phone: '',
      levelName: '',
      discountRate: 1,
      points: 0,
      balance: 0,
      status: 1,
    }
    // 折扣率重新检索一次，保证最新等级
    searchMembers(detail.memberName ?? '').then((list) => {
      const m = list.find((x) => x.id === detail.memberId)
      if (m) member.value = m
    })
  }
  orderRateInput.value = detail.orderRate
  discountReasonApplied.value = detail.discountReason ?? ''
  // 挂单不落密码（安全），取单后结算时须重新录入授权密码
  orderAuth.username = detail.authorizerUsername ?? ''
  orderAuth.password = ''
  remark.value = detail.remark ?? ''
  holdDialog.holds = await listHolds().catch(() => [])
  holdDialog.visible = false
  refreshHoldCount()
  notify.success(`已取单：${detail.itemCount} 件商品已恢复，请核对后结算`)
  focusInput()
}

async function removeHold(hold: HoldVO) {
  try {
    await confirm(
      `确认删除 ${fmtHoldTime(hold.holdTime)} 的挂单（${hold.itemCount} 件，￥${money(hold.amount)}）？删除后不可恢复。`,
      '删除挂单',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteHold(hold.id)
    holdDialog.holds = await listHolds().catch(() => [])
    refreshHoldCount()
    notify.success('挂单已删除')
  } catch {
    // 拦截器已提示（如已被取单）
  }
}

// ---------- 键盘操作（NFR-USE-02：回车录入 / F2 结算 / F4 挂单 / Esc 收起） ----------
/** 任一弹窗可见：Esc 交给弹窗自身处理（关闭当前弹窗） */
const anyDialogOpen = computed(() =>
  weightDialog.visible || browseDialog.visible || tempDialog.visible || memberDialog.visible ||
  orderRateDialog.visible || overrideDialog.visible || payDialog.visible || doneDialog.visible ||
  holdDialog.visible,
)

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'F2') {
    e.preventDefault()
    if (!payDialog.visible && !doneDialog.visible) openPayDialog()
  } else if (e.key === 'F4') {
    e.preventDefault()
    holdOrder()
  } else if (e.key === 'Escape' && !anyDialogOpen.value) {
    // 无弹窗时 Esc 收起：清空录入与候选（IR-UI-02 收银台键盘操作）
    input.value = ''
    candidates.value = []
    candidateVisible.value = false
    focusInput()
  }
}

function focusInput() {
  nextTick(() => inputElement()?.focus())
}

/** 录入框强制英数模式（IR-HW-01⑤）：阻断输入法组合，扫码不受输入法状态影响 */
function blockIme(e: Event) {
  composing = true
  e.preventDefault()
}

function onCompositionEnd() {
  composing = false
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  const el = inputElement()
  el?.addEventListener('compositionstart', blockIme)
  el?.addEventListener('compositionend', onCompositionEnd)
  loadBizParams()
  loadPayTypes()
  refreshHoldCount()
  focusInput()
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  const el = inputElement()
  el?.removeEventListener('compositionstart', blockIme)
  el?.removeEventListener('compositionend', onCompositionEnd)
})
</script>

<template>
  <div class="cashier-page">
    <!-- 顶部：录入区（FR-SALE-01 自动聚焦） -->
    <UCard class="input-card">
      <div class="input-row">
        <span class="counter-label">收银台</span>
        <div class="counter-input">
          <AppInputNumber
            v-model="counterNo"
            :min="1"
            :max="99"
            :controls="false"
            size="large"
            @change="saveCounter"
          />
        </div>
        <div ref="inputRef" class="scan-input">
          <AppInput
            v-model="input"
            size="large"
            class="w-full"
            placeholder="扫码 / 输入条码、编码、PLU、拼音（英数模式）后回车"
            clearable
            autocomplete="off"
            spellcheck="false"
            inputmode="text"
            prefix-icon="Search"
            :loading="searching"
            @input="onScanInput"
            @keyup.enter="onInputEnter"
          />
        </div>
        <UButton
          size="lg"
          color="neutral"
          variant="outline"
          class="temp-btn"
          label="分类浏览"
          @click="openBrowse"
        />
        <UButton
          size="lg"
          color="neutral"
          variant="outline"
          class="temp-btn"
          label="无码商品"
          @click="openTempDialog"
        />
        <div class="hotkeys">回车 录入 ｜ F2 结算 ｜ F4 挂单 ｜ Esc 清空</div>
        <UButton
          variant="link"
          size="sm"
          color="neutral"
          class="exit-link"
          label="退出收银"
          @click="$router.push('/home')"
        />
      </div>

      <!-- 候选列表（FR-SALE-02 关键字检索） -->
      <div v-if="candidateVisible" class="candidates">
        <AppTable
          :data="candidates"
          size="small"
          :max-height="260"
          highlight-current-row
          @row-click="pickCandidate"
        >
          <AppTableColumn prop="goodsCode" label="编码" width="120" />
          <AppTableColumn prop="name" label="名称" min-width="180">
            <template #default="{ row }">
              {{ row.name }}<span v-if="row.spec" class="spec">（{{ row.spec }}）</span>
            </template>
          </AppTableColumn>
          <AppTableColumn prop="barcode" label="条码" width="140" />
          <AppTableColumn prop="unitName" label="单位" width="60" align="center" />
          <AppTableColumn label="零售价" width="90" align="right">
            <template #default="{ row }">￥{{ money(row.retailPrice) }}</template>
          </AppTableColumn>
          <AppTableColumn label="称重" width="60" align="center">
            <template #default="{ row }">
              <UBadge v-if="row.isWeighed === 1" size="sm" color="warning" variant="soft">
                称重
              </UBadge>
            </template>
          </AppTableColumn>
        </AppTable>
        <div class="candidates-tip">点击行选择商品（称重商品将要求输入净重）</div>
      </div>
    </UCard>

    <div class="main-area">
      <!-- 左侧：明细区（FR-SALE-01/03） -->
      <UCard class="cart-card">
        <template #header>
          <div class="card-head">
            <span>销售明细（{{ cart.length }} 行 / {{ itemCount }} 件）</span>
            <UButton
              color="error"
              variant="ghost"
              :disabled="cart.length === 0"
              label="整单清空"
              @click="clearCart"
            />
          </div>
        </template>
        <AppTable
          :data="cart"
          max-height="100%"
          class="cart-table"
          empty-text="暂无商品，请扫码或搜索录入"
        >
          <AppTableColumn type="index" label="#" width="44" align="center" />
          <AppTableColumn label="商品名称" min-width="200">
            <template #default="{ row }">
              <div class="goods-name">
                {{ row.name }}
                <UBadge v-if="row.isWeighed === 1" size="sm" color="warning" variant="soft">
                  称重
                </UBadge>
                <UBadge v-if="row.lineType === 'TEMP'" size="sm" color="neutral" variant="soft">
                  非档案商品
                </UBadge>
              </div>
              <div v-if="row.spec || row.barcode" class="goods-sub">
                <span v-if="row.spec">{{ row.spec }}</span>
                <span v-if="row.barcode">{{ row.barcode }}</span>
              </div>
            </template>
          </AppTableColumn>
          <AppTableColumn label="单价" width="118" align="right">
            <template #default="{ row }">
              <AppInputNumber
                v-if="row.lineType === 'TEMP'"
                v-model="row.retailPrice"
                :min="0.01"
                :precision="2"
                :controls="false"
                size="small"
                style="width: 86px"
              />
              <template v-else>
                <span :class="{ 'override-price': row.overridePrice != null }">
                  ￥{{ money(row.overridePrice ?? row.retailPrice) }}
                </span>
                <UTooltip
                  v-if="row.overridePrice != null"
                  :text="`原价 ￥${money(row.retailPrice)}｜改价原因：${row.priceReason}`"
                  :content="{ side: 'top' }"
                >
                  <UBadge size="sm" color="error" variant="soft" class="override-tag">改价</UBadge>
                </UTooltip>
                <span class="unit">/{{ row.unitName }}</span>
              </template>
            </template>
          </AppTableColumn>
          <AppTableColumn label="数量" width="170" align="center">
            <template #default="{ row }">
              <span v-if="row.lineType === 'TEMP'" class="temp-qty">1</span>
              <AppInputNumber
                v-else
                v-model="row.quantity"
                :min="row.isWeighed === 1 ? 0.001 : 1"
                :precision="row.isWeighed === 1 ? 3 : 0"
                :step="row.isWeighed === 1 ? 0.1 : 1"
                size="small"
                @change="onQtyChange(row as CartLine)"
              />
            </template>
          </AppTableColumn>
          <AppTableColumn label="行备注" width="130">
            <template #default="{ row }">
              <AppInput v-model="row.remark" size="small" placeholder="选填" />
            </template>
          </AppTableColumn>
          <AppTableColumn label="小计（预估）" width="120" align="right">
            <template #default="{ row }">
              ￥{{ money((row.overridePrice ?? row.retailPrice) * row.quantity) }}
            </template>
          </AppTableColumn>
          <AppTableColumn label="操作" width="110" align="center">
            <template #default="{ row }">
              <UButton
                v-if="row.lineType !== 'TEMP'"
                variant="link"
                size="sm"
                label="改价"
                @click="openOverrideDialog(row as CartLine)"
              />
              <UButton
                variant="link"
                size="sm"
                color="error"
                label="删除"
                @click="removeLine(row as CartLine)"
              />
            </template>
          </AppTableColumn>
        </AppTable>
      </UCard>

      <!-- 右侧：汇总与操作区（FR-SALE-01） -->
      <div class="summary-col">
        <!-- 会员卡（FR-SALE-05） -->
        <UCard class="member-card">
          <template #header>
            <div class="card-head">
              <span>会员</span>
              <UButton
                v-if="member"
                variant="link"
                size="sm"
                color="error"
                label="移除"
                @click="clearMember"
              />
            </div>
          </template>
          <template v-if="member">
            <div class="member-name">{{ member.name }}</div>
            <div class="member-meta">
              {{ member.levelName }} ｜ 积分 {{ member.points }} ｜ 储值 ￥{{ money(member.balance) }}
              <UBadge v-if="member.status !== 1" size="sm" color="error" variant="soft">已停用</UBadge>
            </div>
            <div v-if="memberDisabledTip" class="member-rate member-disabled">
              该会员已停用：不享受折扣与积分累计
            </div>
            <UAlert
              v-if="memberDisabledTip"
              class="member-disabled-alert"
              color="warning"
              variant="soft"
              icon="i-lucide-triangle-alert"
              title="停用会员按散客结算（BR-MEM-03）：本单无会员优惠、不累计积分"
            />
            <div v-else class="member-rate">等级折扣率：{{ Number(member.discountRate).toFixed(2) }}</div>
          </template>
          <template v-else>
            <UButton
              class="member-btn"
              size="lg"
              color="neutral"
              variant="outline"
              icon="i-lucide-user"
              label="识别会员"
              @click="openMemberDialog"
            />
          </template>
        </UCard>

        <!-- 金额汇总 -->
        <UCard class="amount-card">
          <div class="amount-row"><span>件数</span><b>{{ itemCount }}</b></div>
          <div class="amount-row"><span>商品总额（预估）</span><b>￥{{ money(estGoodsAmount) }}</b></div>
          <div class="amount-row">
            <span>会员折扣</span>
            <b>{{ member ? Number(member.discountRate).toFixed(2) + ' 折' : '—' }}</b>
          </div>
          <div class="amount-row">
            <span>整单折扣</span>
            <b>
              {{ orderRateInput != null && orderRateInput < 1 ? orderRateInput.toFixed(2) + ' 折' : '—' }}
              <UButton
                variant="link"
                size="sm"
                label="设置"
                @click="openOrderRateDialog"
              />
              <UButton
                v-if="orderRateInput != null && orderRateInput < 1"
                variant="link"
                size="sm"
                color="neutral"
                label="清除"
                @click="clearOrderRate"
              />
            </b>
          </div>
          <div v-if="discountReasonApplied" class="amount-note">折扣原因：{{ discountReasonApplied }}</div>
          <div class="amount-row payable">
            <span>应收（预估）</span>
            <b>￥{{ money(estPayable) }}</b>
          </div>
          <!-- 实收/找零常驻汇总区（IR-UI-06：结算面板打开前也可核对，GAP-P1-12） -->
          <USeparator class="amount-divider" />
          <div class="amount-row">
            <span>实收合计</span>
            <b class="change-amount">￥{{ money(paidTotal) }}</b>
          </div>
          <div class="amount-row change-row">
            <span>找零</span>
            <b v-if="estChange >= 0" class="change-amount">￥{{ money(estChange) }}</b>
            <b v-else class="pay-short">收款不足 ￥{{ money(-estChange) }}</b>
          </div>
          <div class="amount-note">促销价 / 会员价以后端结算取价为准（BR-GOODS-04）</div>
          <AppInput
            v-model="remark"
            placeholder="单据备注（选填）"
            size="small"
            class="order-remark"
          />
        </UCard>

        <!-- 操作按钮（FR-SALE-01：结算/挂单/清空/会员/退货） -->
        <div class="action-col">
          <UButton
            size="lg"
            class="settle-btn"
            label="结算（F2）"
            @click="openPayDialog"
          />
          <div class="action-row">
            <UButton
              size="lg"
              color="neutral"
              variant="outline"
              label="挂单（F4）"
              @click="holdOrder"
            />
            <UButton size="lg" color="neutral" variant="outline" @click="openHoldDialog">
              取单
              <UBadge
                v-if="holdCount > 0"
                :label="holdCount"
                color="error"
                size="sm"
                class="hold-badge"
              />
            </UButton>
          </div>
          <div class="action-row">
            <UButton size="lg" color="neutral" variant="outline" label="清空" @click="clearCart" />
            <UButton
              size="lg"
              color="neutral"
              variant="outline"
              label="退货"
              @click="$router.push('/sale/return')"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 称重商品净重录入（FR-SALE-04） -->
    <AppModal
      v-model="weightDialog.visible"
      :title="`净重录入：${weightDialog.goods?.name ?? ''}`"
      width="380px"
      :close-on-click-modal="false"
    >
      <AppForm label-width="90px" @submit.prevent>
        <AppFormItem :label="`净重(${weightDialog.goods?.unitName ?? 'kg'})`">
          <AppInputNumber
            v-model="weightDialog.netWeight"
            :min="0.001"
            :precision="3"
            :controls="false"
            autofocus
            placeholder="支持 3 位小数"
            style="width: 100%"
            @keyup.enter="confirmWeight"
          />
        </AppFormItem>
        <AppFormItem v-if="weightDialog.goods" label="单价">
          ￥{{ money(weightDialog.goods.retailPrice) }} / {{ weightDialog.goods.unitName }}
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton
          color="neutral"
          variant="outline"
          label="取消"
          @click="weightDialog.visible = false"
        />
        <UButton label="加入明细" @click="confirmWeight" />
      </template>
    </AppModal>

    <!-- 无码商品临时登记（FR-SALE-13）：名称+金额直接入单 -->
    <AppModal
      v-model="tempDialog.visible"
      title="无码商品临时登记"
      width="400px"
      :close-on-click-modal="false"
    >
      <UAlert
        color="info"
        variant="soft"
        icon="i-lucide-info"
        title="适用于条码缺失的临时商品：录入名称与金额直接计入本单"
        description="不关联商品档案、不扣减库存、不支持退货；单据明细将标记“非档案商品”，便于日结对账。"
        class="dialog-alert"
      />
      <AppForm label-width="70px" @submit.prevent>
        <AppFormItem label="名称">
          <AppInput
            v-model="tempDialog.name"
            maxlength="100"
            placeholder="如：代客加工 / 散装赠品"
            @keyup.enter="confirmTempItem"
          />
        </AppFormItem>
        <AppFormItem label="金额">
          <AppInputNumber
            v-model="tempDialog.amount"
            :min="0.01"
            :precision="2"
            :controls="false"
            placeholder="本行金额（元）"
            style="width: 100%"
            @keyup.enter="confirmTempItem"
          />
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton
          color="neutral"
          variant="outline"
          label="取消"
          @click="tempDialog.visible = false"
        />
        <UButton label="加入明细" @click="confirmTempItem" />
      </template>
    </AppModal>

    <!-- 分类树浏览点选（FR-SALE-02③）：左分类树右商品，点击行入单 -->
    <AppModal v-model="browseDialog.visible" title="分类浏览选品" width="860px">
      <div class="browse-layout">
        <div class="browse-tree">
          <UButton
            size="sm"
            :color="browseDialog.categoryId === null ? 'primary' : 'neutral'"
            :variant="browseDialog.categoryId === null ? 'solid' : 'outline'"
            class="browse-all-btn"
            label="全部商品"
            @click="browseDialog.categoryId = null; loadBrowseGoods()"
          />
          <!-- 整树展开展示（原 el-tree default-expand-all）：高亮当前分类，停用分类置灰 -->
          <div class="category-tree" role="tree">
            <div
              v-for="item in browseCategoryRows"
              :key="item.node.id"
              class="app-tree-node category-node"
              :class="{
                'is-current': browseDialog.categoryId === item.node.id,
                'node-disabled': item.node.status !== 1,
              }"
              :style="{ paddingLeft: `${item.depth * 14 + 8}px` }"
              role="treeitem"
              :aria-selected="browseDialog.categoryId === item.node.id"
              @click="pickBrowseCategory(item.node)"
            >
              {{ item.node.name }}
            </div>
          </div>
        </div>
        <AppTable
          :loading="browseDialog.loading"
          :data="browseDialog.goods"
          size="small"
          :max-height="440"
          highlight-current-row
          class="browse-table"
          empty-text="该分类下暂无启用商品"
          @row-click="pickBrowseGoods"
        >
          <AppTableColumn prop="barcode" label="条码" width="130" />
          <AppTableColumn label="名称" min-width="160">
            <template #default="{ row }">
              {{ row.name }}<span v-if="row.spec" class="spec">（{{ row.spec }}）</span>
            </template>
          </AppTableColumn>
          <AppTableColumn prop="unitName" label="单位" width="56" align="center" />
          <AppTableColumn label="零售价" width="86" align="right">
            <template #default="{ row }">￥{{ money(row.retailPrice) }}</template>
          </AppTableColumn>
          <AppTableColumn label="称重" width="56" align="center">
            <template #default="{ row }">
              <UBadge v-if="row.isWeighed === 1" size="sm" color="warning" variant="soft">
                称重
              </UBadge>
            </template>
          </AppTableColumn>
        </AppTable>
      </div>
      <template #footer>
        <span class="browse-tip">点击商品行加入明细（称重商品将要求输入净重）</span>
        <UButton
          color="neutral"
          variant="outline"
          label="关闭"
          @click="browseDialog.visible = false"
        />
      </template>
    </AppModal>

    <!-- 会员检索（FR-SALE-05） -->
    <AppModal v-model="memberDialog.visible" title="会员识别" width="560px" @open="focusInput">
      <div class="member-search-row">
        <AppInput
          v-model="memberDialog.keyword"
          placeholder="手机号 / 会员编号 / 姓名"
          clearable
          @keyup.enter="doSearchMembers"
        />
        <UButton :loading="memberDialog.loading" label="查询" @click="doSearchMembers" />
      </div>
      <AppTable
        v-if="memberCandidates.length"
        :data="memberCandidates"
        size="small"
        :max-height="300"
        highlight-current-row
        @row-click="pickMember"
      >
        <AppTableColumn prop="memberNo" label="会员编号" width="120" />
        <AppTableColumn prop="name" label="姓名" width="90" />
        <AppTableColumn prop="phone" label="手机号" width="130" />
        <AppTableColumn prop="levelName" label="等级" width="90" />
        <AppTableColumn label="折扣率" width="80" align="center">
          <template #default="{ row }">{{ Number(row.discountRate).toFixed(2) }}</template>
        </AppTableColumn>
        <AppTableColumn prop="points" label="积分" width="70" align="right" />
        <AppTableColumn label="储值余额" width="95" align="right">
          <template #default="{ row }">￥{{ money(row.balance) }}</template>
        </AppTableColumn>
        <AppTableColumn label="状态" width="70" align="center">
          <template #default="{ row }">
            <UBadge
              :color="row.status === 1 ? 'success' : 'error'"
              size="sm"
              variant="soft"
            >
              {{ row.status === 1 ? '正常' : '停用' }}
            </UBadge>
          </template>
        </AppTableColumn>
      </AppTable>
    </AppModal>

    <!-- 整单折扣（FR-SALE-07 店长授权） -->
    <AppModal
      v-model="orderRateDialog.visible"
      title="整单折扣（店长授权）"
      width="400px"
      :close-on-click-modal="false"
    >
      <UAlert
        color="info"
        variant="soft"
        icon="i-lucide-info"
        title="收银员不可改价；整单折扣须店长授权（FR-SALE-03/07）"
        class="dialog-alert"
      />
      <AppForm label-width="90px" @submit.prevent>
        <AppFormItem label="折扣率">
          <AppInputNumber
            v-model="orderRateDialog.rate"
            :min="bizParams.minDiscountRate"
            :max="1"
            :step="0.05"
            :precision="2"
            :controls="false"
            style="width: 100%"
          />
          <div class="form-tip">范围 {{ bizParams.minDiscountRate }}~1（BR-SALE-10），1 为不打折</div>
        </AppFormItem>
        <AppFormItem
          v-if="orderRateDialog.rate != null && orderRateDialog.rate < 1"
          label="折扣原因"
          required
        >
          <AppInput
            v-model="orderRateDialog.discountReason"
            maxlength="200"
            placeholder="必填，如：店庆促销 / 批量采购"
          />
        </AppFormItem>
        <AppFormItem
          v-if="orderRateDialog.rate != null && orderRateDialog.rate < 1"
          label="授权账号"
        >
          <AppInput
            v-model="orderRateDialog.authorizerUsername"
            placeholder="店长登录账号（≠ 当前收银员）"
          />
        </AppFormItem>
        <AppFormItem
          v-if="orderRateDialog.rate != null && orderRateDialog.rate < 1"
          label="授权密码"
          required
        >
          <AppInput
            v-model="orderRateDialog.authorizerPassword"
            type="password"
            show-password
            maxlength="50"
            placeholder="店长账号密码（身份校验，FR-SALE-07）"
          />
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton
          color="neutral"
          variant="outline"
          label="取消"
          @click="orderRateDialog.visible = false"
        />
        <UButton label="确定" @click="confirmOrderRate" />
      </template>
    </AppModal>

    <!-- 单品改价（FR-SALE-07 店长授权） -->
    <AppModal
      v-model="overrideDialog.visible"
      :title="`单品改价：${overrideDialog.line?.name ?? ''}`"
      width="430px"
      :close-on-click-modal="false"
    >
      <UAlert
        color="warning"
        variant="soft"
        icon="i-lucide-triangle-alert"
        title="收银员不可改价：改价须店长现场输入账号密码授权并填写原因（FR-SALE-03/07）"
        class="dialog-alert"
      />
      <AppForm label-width="90px" @submit.prevent>
        <AppFormItem label="原单价">
          ￥{{ money(overrideDialog.line?.retailPrice ?? 0) }} / {{ overrideDialog.line?.unitName ?? '' }}
        </AppFormItem>
        <AppFormItem label="改价单价" required>
          <AppInputNumber
            v-model="overrideDialog.price"
            :min="0.01"
            :precision="2"
            :controls="false"
            placeholder="按新单价成交"
            style="width: 100%"
            @keyup.enter="confirmOverride"
          />
        </AppFormItem>
        <AppFormItem label="改价原因" required>
          <AppInput
            v-model="overrideDialog.reason"
            maxlength="200"
            placeholder="必填，如：临期折让 / 生鲜品相"
            @keyup.enter="confirmOverride"
          />
        </AppFormItem>
        <AppFormItem label="授权账号" required>
          <AppInput
            v-model="overrideDialog.authorizerUsername"
            placeholder="店长登录账号（≠ 当前收银员）"
          />
        </AppFormItem>
        <AppFormItem label="授权密码" required>
          <AppInput
            v-model="overrideDialog.authorizerPassword"
            type="password"
            show-password
            maxlength="50"
            placeholder="店长账号密码（身份校验，FR-SALE-07）"
            @keyup.enter="confirmOverride"
          />
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton
          v-if="overrideDialog.line?.overridePrice != null"
          color="neutral"
          variant="outline"
          label="取消改价"
          @click="cancelOverride"
        />
        <UButton
          color="neutral"
          variant="outline"
          label="关闭"
          @click="overrideDialog.visible = false"
        />
        <UButton label="确认改价" @click="confirmOverride" />
      </template>
    </AppModal>

    <!-- 收款结算（FR-SALE-06：一单多方式） -->
    <AppModal
      v-model="payDialog.visible"
      title="收款结算"
      width="520px"
      :close-on-click-modal="false"
    >
      <div class="pay-summary">
        <div class="pay-row"><span>件数</span><b>{{ itemCount }}</b></div>
        <div class="pay-row">
          <span>应收{{ previewLoading ? '（计算中）' : payPreview ? '（结算）' : '（预估）' }}</span>
          <b class="payable">￥{{ money(payableForPay) }}</b>
        </div>
      </div>
      <div v-for="(line, idx) in payDialog.lines" :key="idx" class="pay-line">
        <AppSelect v-model="line.payType" style="width: 120px">
          <AppOption
            v-for="opt in payTypeOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
            :disabled="opt.value === 'BALANCE' && (!member || member.status !== 1)"
          />
        </AppSelect>
        <AppInputNumber
          v-model="line.amount"
          :min="0.01"
          :precision="2"
          :controls="false"
          placeholder="收款金额"
          style="flex: 1"
        />
        <UButton
          v-if="payDialog.lines.length > 1"
          variant="link"
          color="error"
          size="sm"
          label="删除"
          @click="removePayLine(idx)"
        />
      </div>
      <div class="pay-actions">
        <UButton
          size="sm"
          color="neutral"
          variant="outline"
          label="+ 添加收款方式"
          @click="addPayLine"
        />
        <UButton
          size="sm"
          color="neutral"
          variant="outline"
          label="金额=应收"
          :disabled="previewLoading"
          @click="fillExact"
        />
        <UButton
          v-if="member && member.status === 1"
          size="sm"
          color="warning"
          :disabled="previewLoading"
          @click="fillBalance"
        >
          储值￥{{ money(balanceCap) }}
        </UButton>
        <template v-if="payDialog.lines[0]?.payType === 'CASH'">
          <UButton
            v-for="c in CASH_QUICK"
            :key="c"
            size="sm"
            color="neutral"
            variant="outline"
            :label="`￥${c}`"
            @click="fillCash(c)"
          />
        </template>
      </div>
      <div class="pay-summary">
        <div class="pay-row"><span>实收合计</span><b>￥{{ money(paidTotal) }}</b></div>
        <div class="pay-row" :class="{ short: estChange < 0 }">
          <span>{{ estChange >= 0 ? '找零（预估）' : '收款不足' }}</span>
          <b :class="{ 'change-amount': estChange >= 0 }">￥{{ money(Math.abs(estChange)) }}</b>
        </div>
      </div>
      <div class="pay-note">实收不得低于应收；实际应收以后端结算取价为准，如有差异以结算结果为准</div>
      <template #footer>
        <UButton
          color="neutral"
          variant="outline"
          label="取消（Esc）"
          @click="payDialog.visible = false"
        />
        <UButton
          :loading="payDialog.submitting"
          :disabled="previewLoading"
          label="确认结算"
          @click="submitCheckout"
        />
      </template>
    </AppModal>

    <!-- 结算成功（FR-SALE-06 小票回显） -->
    <AppModal
      v-model="doneDialog.visible"
      title="结算成功"
      width="420px"
      :close-on-click-modal="false"
    >
      <div v-if="doneDialog.result" class="done-body">
        <div class="done-icon">
          <UIcon name="i-lucide-circle-check-big" :size="42" style="color: #67c23a" />
        </div>
        <div class="done-order">{{ doneDialog.result.orderNo }}</div>
        <UAlert
          v-if="doneDialog.result.memberLevelUpgraded"
          color="success"
          variant="soft"
          icon="i-lucide-circle-check-big"
          class="upgrade-alert"
          :title="`恭喜！会员已升级为【${doneDialog.result.memberLevelUpgraded}】（BR-MEM-06）`"
          description="升级折扣率与新积分倍率自下一单生效"
        />
        <div class="done-grid">
          <span>件数</span><b>{{ doneDialog.result.itemCount }}</b>
          <span>商品总额</span><b>￥{{ money(doneDialog.result.goodsAmount) }}</b>
          <span>优惠合计</span><b>-￥{{ money(doneDialog.result.discountAmount) }}</b>
          <span>应收</span><b>￥{{ money(doneDialog.result.payableAmount) }}</b>
          <span>实收</span><b>￥{{ money(doneDialog.result.paidAmount) }}</b>
          <span>找零</span><b class="change">￥{{ money(doneDialog.result.changeAmount) }}</b>
        </div>
        <div class="done-pays">
          <span v-for="(p, i) in doneDialog.result.payments" :key="i" class="pay-tag">
            {{ PAY_TYPE_MAP[p.payType] ?? p.payType }} ￥{{ money(p.amount) }}
          </span>
        </div>
      </div>
      <template #footer>
        <UButton color="neutral" variant="outline" label="打印小票" @click="printDoneReceipt" />
        <UButton label="完成" @click="closeDone" />
      </template>
    </AppModal>

    <!-- 挂单列表（FR-SALE-08：落库 hold_order，仅本人可见，超 24 小时自动清理） -->
    <AppModal v-model="holdDialog.visible" title="挂单列表" width="660px">
      <AppTable v-if="holdDialog.holds.length" :data="holdDialog.holds" size="small">
        <AppTableColumn label="挂单时间" width="150">
          <template #default="{ row }">{{ fmtHoldTime(row.holdTime) }}</template>
        </AppTableColumn>
        <AppTableColumn prop="counterNo" label="台号" width="60" align="center" />
        <AppTableColumn prop="memberName" label="会员" width="90">
          <template #default="{ row }">{{ row.memberName ?? '散客' }}</template>
        </AppTableColumn>
        <AppTableColumn prop="itemCount" label="件数" width="70" align="right" />
        <AppTableColumn label="金额" width="100" align="right">
          <template #default="{ row }">￥{{ money(row.amount) }}</template>
        </AppTableColumn>
        <AppTableColumn prop="remark" label="备注" min-width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="130" align="center">
          <template #default="{ row }">
            <UButton variant="link" size="sm" label="取单" @click="resumeHold(row as HoldVO)" />
            <UButton
              variant="link"
              size="sm"
              color="error"
              label="删除"
              @click="removeHold(row as HoldVO)"
            />
          </template>
        </AppTableColumn>
      </AppTable>
      <UEmpty
        v-else
        icon="i-lucide-inbox"
        description="暂无挂单"
        :ui="{ avatar: 'size-14' }"
      />
      <div class="muted note">挂单不扣库存、不计入报表（BR-RPT-03）；仅本人可见，超过 24 小时未取将自动清理（IR-SW-06）。</div>
    </AppModal>
  </div>
</template>

<style scoped lang="scss">
.cashier-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 10px;

  .input-card {
    :deep([data-slot='body']) {
      padding: 12px 16px;
    }

    .input-row {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 10px;
      row-gap: 8px;

      .counter-label {
        font-size: 13px;
        color: var(--el-text-color-regular);
        white-space: nowrap;
        flex-shrink: 0;
      }

      /* AppInputNumber 的 style 会落到内部 input，根节点仍是 w-full；
         这里用固定宽外层约束根节点，避免它挤占检索框宽度（收银台窄屏重叠问题） */
      .counter-input {
        flex: 0 0 72px;
        width: 72px;
      }

      .scan-input {
        flex: 1 1 260px;
        min-width: 180px;

        :deep(input) {
          font-size: 15px;
        }
      }

      .temp-btn {
        flex-shrink: 0;
        font-weight: 500;
        white-space: nowrap;
        /* 关键按钮 ≥44px 支持触屏（IR-UI-06：收银台专项，GAP-P1-12） */
        min-height: 44px;
      }

      .hotkeys {
        flex-shrink: 0;
        font-size: 12px;
        color: var(--el-text-color-secondary);
        white-space: nowrap;
      }

      .exit-link {
        flex-shrink: 0;
        margin-left: auto;
        white-space: nowrap;
      }
    }

    /* 窄屏优先保证检索框与操作按钮的可用宽度，快捷键提示收起 */
    @media (max-width: 1100px) {
      .input-row .hotkeys {
        display: none;
      }
    }

    .candidates {
      margin-top: 10px;

      .candidates-tip {
        margin-top: 6px;
        font-size: 12px;
        color: var(--el-text-color-secondary);
      }
    }
  }

  .browse-layout {
    display: flex;
    gap: 12px;

    .browse-tree {
      width: 200px;
      flex-shrink: 0;
      border-right: 1px solid var(--el-border-color-lighter);
      padding-right: 8px;
      overflow-y: auto;
      max-height: 440px;

      .browse-all-btn {
        width: 100%;
        margin-bottom: 8px;
      }

      .category-tree {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }

      .category-node {
        padding-top: 6px;
        padding-bottom: 6px;
        font-size: 13px;
        line-height: 1.4;
        color: var(--el-text-color-regular);
        border-radius: 6px;
        cursor: pointer;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;

        &:hover {
          background: var(--el-fill-color-light);
        }

        &.is-current {
          background: var(--el-color-primary);
          color: #fff;
          font-weight: 500;
        }
      }

      .node-disabled {
        color: var(--el-text-color-placeholder);
        cursor: not-allowed;
      }
    }

    .browse-table {
      flex: 1;
      min-width: 0;
    }
  }

  .browse-tip {
    margin-right: auto;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .main-area {
    flex: 1;
    display: flex;
    gap: 10px;
    min-height: 0;

    .cart-card {
      flex: 1;
      display: flex;
      flex-direction: column;
      min-width: 0;

      :deep([data-slot='body']) {
        flex: 1;
        min-height: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
      }

      .cart-table {
        height: 100%;

        /* 明细行字号 ≥16px（IR-UI-06 收银台专项，GAP-P2-01） */
        :deep(td) {
          font-size: 16px;
        }
      }

      .goods-name {
        font-weight: 500;
      }

      .temp-qty {
        color: var(--el-text-color-secondary);
      }

      .goods-sub {
        font-size: 12px;
        color: var(--el-text-color-secondary);

        span + span::before {
          content: ' · ';
        }
      }

      .unit {
        font-size: 12px;
        color: var(--el-text-color-secondary);
      }

      .override-price {
        color: var(--el-color-danger);
        font-weight: 600;
      }

      .override-tag {
        margin-left: 4px;
        transform: scale(0.9);
      }
    }

    .summary-col {
      width: 330px;
      display: flex;
      flex-direction: column;
      gap: 10px;

      .card-head {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }

      .member-card {
        .member-name {
          font-size: 16px;
          font-weight: 600;
        }

        .member-meta {
          margin-top: 4px;
          font-size: 13px;
          color: var(--el-text-color-regular);
        }

        .member-rate {
          margin-top: 4px;
          font-size: 13px;
          color: var(--el-color-primary);
        }

        /* 停用会员常驻提示（BR-MEM-03/GAP-P2-02） */
        .member-disabled {
          color: var(--el-color-warning);
        }

        .member-disabled-alert {
          margin-top: 8px;
        }

        .member-btn {
          width: 100%;
        }
      }

      .amount-card {
        :deep([data-slot='body']) {
          padding: 14px 16px;
        }

        .amount-row {
          display: flex;
          justify-content: space-between;
          align-items: center;
          padding: 5px 0;
          font-size: 14px;
          color: var(--el-text-color-regular);

          b {
            font-variant-numeric: tabular-nums;
          }
        }

        .payable {
          margin-top: 4px;
          padding-top: 10px;
          border-top: 1px dashed var(--el-border-color);
          font-size: 16px;

          /* 应收金额 ≥32px 加粗（IR-UI-06 硬指标，GAP-P2-01） */
          b {
            color: var(--el-color-danger);
            font-size: 32px;
            font-weight: 700;
          }
        }

        /* 找零 ≥24px（IR-UI-06 硬指标，GAP-P2-01） */
        .change-row {
          .change-amount {
            font-size: 24px;
            font-weight: 700;
            color: var(--el-color-warning);
          }

          b {
            font-size: 24px;
          }
        }

        .amount-divider {
          margin: 8px 0 2px;
        }

        .pay-short {
          color: var(--el-color-warning);
        }

        .amount-note {
          margin-top: 6px;
          font-size: 12px;
          color: var(--el-text-color-placeholder);
        }

        .order-remark {
          margin-top: 8px;
        }
      }

      .action-col {
        display: flex;
        flex-direction: column;
        gap: 10px;

        .settle-btn {
          height: 58px;
          font-size: 18px;
        }

        .action-row {
          display: flex;
          gap: 10px;

          :deep(button) {
            flex: 1;
            margin-left: 0;
          }
        }

        .hold-badge {
          margin-left: 4px;
        }
      }
    }
  }

  .dialog-alert {
    margin-bottom: 12px;
  }

  .form-tip {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    line-height: 1.4;
  }

  .member-search-row {
    display: flex;
    gap: 8px;
    margin-bottom: 12px;
  }

  .pay-summary {
    margin-bottom: 12px;
    padding: 10px 14px;
    background: var(--el-bg-color-page);
    border-radius: 6px;

    .pay-row {
      display: flex;
      justify-content: space-between;
      padding: 3px 0;
      font-size: 14px;
      color: var(--el-text-color-regular);

      /* 应收金额 ≥32px 加粗（IR-UI-06 硬指标，GAP-P2-01） */
      .payable {
        color: var(--el-color-danger);
        font-size: 32px;
        font-weight: 700;
      }

      /* 找零 ≥24px（IR-UI-06 硬指标，GAP-P2-01） */
      .change-amount {
        font-size: 24px;
        font-weight: 700;
        color: var(--el-color-warning);
      }

      &.short b {
        color: var(--el-color-danger);
      }
    }
  }

  .pay-line {
    display: flex;
    gap: 8px;
    align-items: center;
    margin-bottom: 8px;
  }

  .pay-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 12px;
  }

  .pay-note {
    font-size: 12px;
    color: var(--el-text-color-placeholder);
  }

  .done-body {
    text-align: center;

    .upgrade-alert {
      margin-bottom: 10px;
      text-align: left;
    }

    .done-icon {
      margin-bottom: 4px;
    }

    .done-order {
      font-size: 17px;
      font-weight: 600;
      color: var(--el-text-color-primary);
      margin-bottom: 12px;
      font-variant-numeric: tabular-nums;
    }

    .done-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 4px 24px;
      text-align: left;
      font-size: 14px;
      color: var(--el-text-color-regular);
      padding: 10px 14px;
      background: var(--el-bg-color-page);
      border-radius: 6px;

      b {
        text-align: right;
        font-variant-numeric: tabular-nums;
      }

      .change {
        color: var(--el-color-warning);
      }
    }

    .done-pays {
      margin-top: 10px;

      .pay-tag {
        display: inline-block;
        margin: 0 4px;
        padding: 2px 10px;
        font-size: 13px;
        background: var(--el-color-primary-light-9);
        color: var(--el-color-primary);
        border-radius: 4px;
      }
    }
  }

  .spec {
    color: var(--el-text-color-secondary);
  }

  .muted {
    color: var(--el-text-color-secondary);
  }

  .note {
    margin-top: 10px;
    font-size: 12px;
    line-height: 1.5;
  }
}
</style>

import { http } from './request'
import { runExportTask } from './task'
import type { PageResult } from './request'

// =====================================================================
// 经营概览看板（FR-RPT-01）
// =====================================================================

/** 看板 KPI（今日） */
export interface DashboardKpi {
  salesAmount: number
  orderCount: number
  /** 毛利额（仅店长及以上可见，BR-RPT-07） */
  profitAmount: number | null
  /** 毛利率（仅店长及以上可见） */
  profitRate: number | null
  newMemberCount: number
  warningCount: number
}

/** 看板待办事项（按角色权限过滤，FR-RPT-01） */
export interface DashboardTodo {
  type: 'PENDING_PURCHASE' | 'OVERDUE_PURCHASE' | 'PENDING_CHECK' | 'STOCKOUT'
  title: string
  count: number
  route: string
}

/** 看板趋势点（近 N 日） */
export interface DashboardTrendPoint {
  bizDate: string
  orderCount: number
  netSalesAmount: number
}

/** 看板 TOP 热销商品 */
export interface DashboardTopGoods {
  goodsId: number
  goodsName: string
  qty: number
  salesAmount: number
}

/** 看板汇总（FR-RPT-01） */
export interface DashboardVO {
  kpi: DashboardKpi
  todos: DashboardTodo[]
  trend: DashboardTrendPoint[]
  topGoods: DashboardTopGoods[]
}

/** 看板汇总：trendDays 支持 7 / 30 */
export function fetchDashboard(trendDays: 7 | 30 = 7) {
  return http.get<DashboardVO>('/report/dashboard', { trendDays })
}

// =====================================================================
// 销售报表（FR-RPT-02）
// =====================================================================

/** 销售报表行（日报/月报/收款方式/小时/收银员/收银台/会员占比通用） */
export interface SalesReportRow {
  groupKey: string
  bizDate: string | null
  orderCount: number
  itemCount: number | null
  salesAmount: number | null
  discountAmount: number | null
  netSalesAmount: number | null
  refundAmount: number | null
  returnCount: number | null
  /** 净销售 = 净销售额 − 退货额 */
  netAmount: number | null
  /** 销售成本（仅店长及以上可见） */
  costAmount: number | null
  /** 毛利额（仅店长及以上可见） */
  profitAmount: number | null
  /** 毛利率（仅店长及以上可见） */
  profitRate: number | null
  avgOrderAmount: number | null
  shareRate: number | null
}

/** 销售报表结果（明细行 + 合计行） */
export interface SalesReportVO {
  rows: SalesReportRow[]
  total: SalesReportRow | null
}

/** 报表查询区间（BR-RPT-04：默认今日，最大跨度 1 年） */
export interface ReportRange {
  beginDate?: string
  endDate?: string
}

/** 销售日报/月报（FR-RPT-02①） */
export function fetchSalesDaily(range: ReportRange, monthly = false) {
  return http.get<SalesReportVO>('/report/sales/daily', { ...range, monthly })
}

/** 按收款方式汇总（FR-RPT-02②） */
export function fetchSalesByPayMethod(range: ReportRange) {
  return http.get<SalesReportRow[]>('/report/sales/by-pay-method', range)
}

/** 按时段（小时）分布（FR-RPT-02③） */
export function fetchSalesByHour(range: ReportRange) {
  return http.get<SalesReportRow[]>('/report/sales/by-hour', range)
}

/** 按收银员业绩汇总（FR-RPT-02④） */
export function fetchSalesByCashier(range: ReportRange) {
  return http.get<SalesReportRow[]>('/report/sales/by-cashier', range)
}

/** 按收银台业绩汇总（FR-RPT-02④） */
export function fetchSalesByCounter(range: ReportRange) {
  return http.get<SalesReportRow[]>('/report/sales/by-counter', range)
}

/** 会员单与散客单占比（FR-RPT-02⑤） */
export function fetchSalesMemberRatio(range: ReportRange) {
  return http.get<SalesReportRow[]>('/report/sales/member-ratio', range)
}

/** 销售报表导出（FR-RPT-08）：view=daily|pay-method|hour|cashier|counter|member-ratio */
export function exportSalesReport(view: string, range: ReportRange, monthly = false) {
  return runExportTask('/report/sales/export', { view, ...range, monthly }, {
    autoSave: `sales-${view}.xlsx`,
  }).then(() => undefined)
}

// =====================================================================
// 商品销售分析（FR-RPT-03）
// =====================================================================

/** 商品分析通用行（排行/品类结构/品牌/滞销复用） */
export interface GoodsRankRow {
  goodsId: number | null
  goodsCode: string | null
  goodsName: string | null
  categoryId: number | null
  categoryName: string | null
  brandId: number | null
  brandName: string | null
  orderCount: number
  qty: number | null
  salesAmount: number | null
  discountAmount: number | null
  netSalesAmount: number | null
  refundAmount: number | null
  /** 销售成本（仅店长及以上可见） */
  costAmount: number | null
  returnCostAmount: number | null
  /** 毛利额（仅店长及以上可见） */
  profitAmount: number | null
  /** 毛利率（仅店长及以上可见） */
  profitRate: number | null
  shareRate: number | null
  stockQty: number | null
  costPrice: number | null
  occupiedAmount: number | null
  retailPrice: number | null
}

/** 商品销售排行（FR-RPT-03①） */
export function fetchGoodsRanking(
  range: ReportRange,
  orderBy: 'qty' | 'salesAmount' | 'profitAmount' | 'netSalesAmount' = 'salesAmount',
  categoryId?: number,
  page = 1,
  size = 20,
) {
  return http.get<PageResult<GoodsRankRow>>('/report/goods/ranking', {
    ...range,
    orderBy,
    categoryId,
    page,
    size,
  })
}

/** 品类销售结构（FR-RPT-03②） */
export function fetchCategoryStructure(range: ReportRange) {
  return http.get<GoodsRankRow[]>('/report/goods/category-structure', range)
}

/** 滞销商品清单（FR-RPT-03③） */
export function fetchSlowMoving(
  range: ReportRange,
  threshold?: number,
  categoryId?: number,
  page = 1,
  size = 20,
) {
  return http.get<PageResult<GoodsRankRow>>('/report/goods/slow-moving', {
    ...range,
    threshold,
    categoryId,
    page,
    size,
  })
}

/** 品牌销售分析（FR-RPT-03④） */
export function fetchBrandSales(range: ReportRange) {
  return http.get<GoodsRankRow[]>('/report/goods/brand-sales', range)
}

/** 商品分析导出（FR-RPT-08）：view=ranking|category-structure|slow-moving|brand-sales */
export function exportGoodsReport(
  view: string,
  range: ReportRange,
  extra?: { orderBy?: string; threshold?: number; categoryId?: number },
) {
  return runExportTask('/report/goods/export', { view, ...range, ...extra }, {
    autoSave: `goods-${view}.xlsx`,
  }).then(() => undefined)
}

// =====================================================================
// 库存报表（FR-RPT-04）
// =====================================================================

/** 库存汇总行（FR-RPT-04①） */
export interface InvSummaryRow {
  categoryId: number | null
  categoryName: string | null
  goodsId: number | null
  goodsCode: string | null
  goodsName: string | null
  goodsCount: number
  stockQty: number
  /** 成本单价（仅店长及以上可见，BR-RPT-07） */
  costPrice: number | null
  /** 库存成本金额（仅店长及以上可见） */
  costAmount: number | null
  retailValue: number
  /** NORMAL正常/OUT缺货/WARNING预警/OVERSTOCK积压 */
  stockStatus: string | null
}

/** 库存预警行（FR-RPT-04②） */
export interface WarningRptRow {
  goodsId: number
  goodsCode: string
  goodsName: string
  categoryName: string | null
  alertType: 'OUT' | 'WARNING' | 'OVERSTOCK'
  stockQty: number
  safetyStockLower: number
  safetyStockUpper: number | null
  gapQty: number
  suggestQty: number | null
  lastChangeTime: string | null
}

/** 库存变动汇总行（FR-RPT-04③） */
export interface InvChangeRow {
  recordType: string
  recordCount: number
  changeQty: number
  /** 变动金额（成本口径，仅店长及以上可见） */
  amount: number | null
}

/** 库存周转行（FR-RPT-04④） */
export interface TurnoverRow {
  goodsId: number | null
  goodsCode: string | null
  goodsName: string | null
  categoryId: number | null
  categoryName: string | null
  stockQty: number
  /** 期末库存金额（成本口径，仅店长及以上可见，BR-RPT-07） */
  costAmount: number | null
  beginAmount: number | null
  avgAmount: number | null
  saleCostAmount: number | null
  returnCostAmount: number | null
  flowAmount: number | null
  turnoverRate: number | null
  turnoverDays: number | null
}

/** 损耗商品行（FR-RPT-04⑤） */
export interface LossGoodsRow {
  goodsId: number
  goodsName: string
  categoryName: string | null
  lossQty: number
  /** 金额为成本口径，仅店长及以上可见（BR-RPT-07） */
  lossAmount: number | null
  checkQty: number
  checkAmount: number | null
  totalAmount: number | null
}

/** 损耗报表结果 */
export interface LossReportVO {
  rows: LossGoodsRow[]
  totalLossAmount: number | null
  totalOverflowAmount: number | null
  beginInventoryAmount: number | null
  lossRate: number | null
}

/** 库存汇总（商品维度，FR-RPT-04①） */
export function fetchInvSummaryByGoods(categoryId?: number, page = 1, size = 20) {
  return http.get<PageResult<InvSummaryRow>>('/report/inventory/summary/goods', {
    categoryId,
    page,
    size,
  })
}

/** 库存汇总（分类维度，FR-RPT-04①） */
export function fetchInvSummaryByCategory() {
  return http.get<InvSummaryRow[]>('/report/inventory/summary/category')
}

/** 库存预警报表（FR-RPT-04②） */
export function fetchInvWarnings(alertType?: string, page = 1, size = 20) {
  return http.get<PageResult<WarningRptRow>>('/report/inventory/warnings', {
    alertType,
    page,
    size,
  })
}

/** 库存变动明细汇总（FR-RPT-04③） */
export function fetchInvChanges(range: ReportRange) {
  return http.get<InvChangeRow[]>('/report/inventory/changes', range)
}

/** 库存周转（商品维度，FR-RPT-04④） */
export function fetchTurnoverByGoods(range: ReportRange, categoryId?: number, page = 1, size = 20) {
  return http.get<PageResult<TurnoverRow>>('/report/inventory/turnover/goods', {
    ...range,
    categoryId,
    page,
    size,
  })
}

/** 库存周转（分类维度，FR-RPT-04④） */
export function fetchTurnoverByCategory(range: ReportRange, categoryId?: number) {
  return http.get<TurnoverRow[]>('/report/inventory/turnover/category', { ...range, categoryId })
}

/** 损耗报表（FR-RPT-04⑤） */
export function fetchLossReport(range: ReportRange) {
  return http.get<LossReportVO>('/report/inventory/loss', range)
}

/** 库存报表导出（FR-RPT-08） */
export function exportInventoryReport(
  view: string,
  range: ReportRange,
  extra?: { alertType?: string; categoryId?: number },
) {
  return runExportTask('/report/inventory/export', { view, ...range, ...extra }, {
    autoSave: `inventory-${view}.xlsx`,
  }).then(() => undefined)
}

// =====================================================================
// 毛利分析（FR-RPT-05）
// =====================================================================

/** 负毛利商品行（FR-RPT-05） */
export interface NegativeProfitRow {
  goodsId: number
  goodsCode: string
  goodsName: string
  categoryName: string | null
  unitPrice: number
  costPrice: number
  unitProfit: number
  qty: number
  orderCount: number
  paidAmount: number
  costAmount: number
  profitAmount: number
}

/** 按商品分析毛利（FR-RPT-05） */
export function fetchProfitByGoods(range: ReportRange, categoryId?: number, page = 1, size = 20) {
  return http.get<PageResult<GoodsRankRow>>('/report/profit/by-goods', {
    ...range,
    categoryId,
    page,
    size,
  })
}

/** 按分类分析毛利（FR-RPT-05） */
export function fetchProfitByCategory(range: ReportRange) {
  return http.get<GoodsRankRow[]>('/report/profit/by-category', range)
}

/** 按时段分析毛利（FR-RPT-05） */
export function fetchProfitByDay(range: ReportRange) {
  return http.get<SalesReportVO>('/report/profit/by-day', range)
}

/** 负毛利商品清单（FR-RPT-05） */
export function fetchNegativeProfit(range: ReportRange) {
  return http.get<NegativeProfitRow[]>('/report/profit/negative', range)
}

/** 毛利分析导出（FR-RPT-08） */
export function exportProfitReport(view: string, range: ReportRange, extra?: { categoryId?: number }) {
  return runExportTask('/report/profit/export', { view, ...range, ...extra }, {
    autoSave: `profit-${view}.xlsx`,
  }).then(() => undefined)
}

// =====================================================================
// 采购报表（FR-RPT-06）
// =====================================================================

/** 采购汇总行（FR-RPT-06①②） */
export interface PurchSummaryRow {
  supplierId: number | null
  supplierName: string | null
  goodsId: number | null
  goodsCode: string | null
  goodsName: string | null
  categoryName: string | null
  orderCount: number
  purchaseQty: number
  /** 金额为成本口径，仅店长及以上可见（BR-RPT-07） */
  purchaseAmount: number | null
  receivedQty: number
  inTransitQty: number
  returnQty: number
  returnAmount: number | null
  settledAmount: number | null
  partialSettledAmount: number | null
  shareRate: number | null
}

/** 采购价格波动点（FR-RPT-06③） */
export interface PriceTrendRow {
  orderId: number
  orderNo: string
  supplierId: number
  supplierName: string
  orderTime: string
  quantity: number
  /** 采购单价（成本信息，仅店长及以上可见） */
  price: number | null
}

/** 到货及时率行（FR-RPT-06④） */
export interface ArrivalRow {
  supplierId: number
  supplierName: string
  receiptCount: number
  onTimeCount: number
  onTimeRate: number | null
}

/** 采购汇总（FR-RPT-06①）：dim=supplier|goods */
export function fetchPurchaseSummary(range: ReportRange, dim: 'supplier' | 'goods' = 'supplier') {
  return http.get<PurchSummaryRow[]>('/report/purchase/summary', { ...range, dim })
}

/** 供应商采购排行与占比（FR-RPT-06②） */
export function fetchSupplierRanking(range: ReportRange) {
  return http.get<PurchSummaryRow[]>('/report/purchase/supplier-ranking', range)
}

/** 采购价格波动分析（FR-RPT-06③） */
export function fetchPriceTrend(goodsId: number, range: ReportRange) {
  return http.get<PriceTrendRow[]>('/report/purchase/price-trend', { goodsId, ...range })
}

/** 到货及时率统计（FR-RPT-06④） */
export function fetchArrival(range: ReportRange) {
  return http.get<ArrivalRow[]>('/report/purchase/arrival', range)
}

/** 采购报表导出（FR-RPT-08） */
export function exportPurchaseReport(
  view: string,
  range: ReportRange,
  extra?: { goodsId?: number },
) {
  return runExportTask('/report/purchase/export', { view, ...range, ...extra }, {
    autoSave: `purchase-${view}.xlsx`,
  }).then(() => undefined)
}

// =====================================================================
// 会员报表（FR-RPT-07）
// =====================================================================

/** 会员报表通用行（增长/排行/等级/沉睡/生日复用） */
export interface MemberRptRow {
  id: number | null
  memberNo: string | null
  name: string | null
  /** 手机号（按角色脱敏，BR-MEM-08） */
  phone: string
  levelName: string | null
  points: number | null
  totalConsume: number | null
  consumeTimes: number | null
  lastConsumeTime: string | null
  registerTime: string | null
  birthday: string | null
  bizDate: string | null
  newCount: number | null
  cumulativeCount: number | null
  memberCount: number | null
  sleepingDays: number | null
}

/** 会员消费贡献（FR-RPT-07④） */
export interface MemberContributionVO {
  memberNetAmount: number
  totalNetAmount: number
  memberOrderCount: number
  totalOrderCount: number
  shareRate: number | null
}

/** 会员增长趋势（FR-RPT-07①） */
export function fetchMemberGrowth(range: ReportRange) {
  return http.get<MemberRptRow[]>('/report/member/growth', range)
}

/** 会员消费排行 TOP N（FR-RPT-07②） */
export function fetchMemberConsumptionRanking(topN = 10) {
  return http.get<MemberRptRow[]>('/report/member/consumption-ranking', { topN })
}

/** 会员等级分布（FR-RPT-07③） */
export function fetchMemberLevelDistribution() {
  return http.get<MemberRptRow[]>('/report/member/level-distribution')
}

/** 会员消费贡献占比（FR-RPT-07④） */
export function fetchMemberContribution(range: ReportRange) {
  return http.get<MemberContributionVO>('/report/member/contribution', range)
}

/** 沉睡会员清单（FR-RPT-07⑤） */
export function fetchSleepingMembers(days = 90, page = 1, size = 20) {
  return http.get<PageResult<MemberRptRow>>('/report/member/sleeping', { days, page, size })
}

/** 会员生日提醒清单（FR-RPT-07⑥）：scope=month|week */
export function fetchBirthdayMembers(scope: 'month' | 'week' = 'week') {
  return http.get<MemberRptRow[]>('/report/member/birthday', { scope })
}

/** 会员报表导出（FR-RPT-08） */
export function exportMemberReport(
  view: string,
  range: ReportRange,
  extra?: { topN?: number; days?: number; scope?: string },
) {
  return runExportTask('/report/member/export', { view, ...range, ...extra }, {
    autoSave: `member-${view}.xlsx`,
  }).then(() => undefined)
}

// =====================================================================
// 汇总数字下钻（BR-RPT-06）：任一汇总数字 → 构成它的明细单据列表
// =====================================================================

/** 下钻销售单行 */
export interface DrillOrderRow {
  id: number
  orderNo: string
  cashierName: string
  counterNo: number
  memberName: string | null
  saleTime: string
  businessDate: string
  itemCount: number
  goodsAmount: number
  discountAmount: number
  payableAmount: number
  paidAmount: number
  costAmount: number | null
  profitAmount: number | null
  returnStatus: string
}

/** 销售单下钻筛选：与对应报表行同口径；日期为空时全量下钻（会员消费排行） */
export interface DrillOrderFilters {
  beginDate?: string
  endDate?: string
  goodsId?: number
  categoryId?: number
  cashierName?: string
  counterNo?: number
  /** true 仅会员单 / false 仅散客单 / 空为全部 */
  memberOnly?: boolean
  memberId?: number
  payType?: string
  hour?: number
  page?: number
  size?: number
}

/** 汇总数字下钻（BR-RPT-06）：构成汇总的销售单列表（商品/销售/毛利/会员报表共用） */
export function fetchDrillOrders(
  report: 'goods' | 'sales' | 'profit' | 'member',
  filters: DrillOrderFilters,
) {
  return http.get<PageResult<DrillOrderRow>>(`/report/${report}/drill-orders`, filters)
}

/** 下钻采购单行（供应商/商品汇总、供应商排行行 → 采购单） */
export interface DrillPurchaseRow {
  id: number
  orderNo: string
  supplierId: number
  supplierName: string
  status: string
  createBy: string | null
  createTime: string
  auditTime: string | null
  expectDate: string | null
  /** 金额为成本口径，仅店长及以上可见（BR-RPT-07） */
  totalAmount: number | null
  receivedAmount: number | null
  returnAmount: number | null
  settleStatus: string
  settleAmount: number | null
}

/** 采购单下钻（BR-RPT-06）：supplierId/goodsId 与汇总行对应 */
export function fetchPurchaseDrillOrders(filters: {
  beginDate?: string
  endDate?: string
  supplierId?: number
  goodsId?: number
  page?: number
  size?: number
}) {
  return http.get<PageResult<DrillPurchaseRow>>('/report/purchase/drill-orders', filters)
}

/** 下钻入库单行（到货及时率供应商行 → 入库单，含按期标记） */
export interface DrillReceiptRow {
  id: number
  receiptNo: string
  purchaseOrderId: number
  purchaseOrderNo: string
  supplierId: number
  supplierName: string
  receiptTime: string
  operatorName: string | null
  deliveryOrderNo: string | null
  /** 入库金额（成本口径，仅店长及以上可见） */
  totalAmount: number | null
  expectDate: string | null
  onTime: boolean | null
}

/** 入库单下钻（BR-RPT-06）：到货及时率行 → 构成统计的入库单列表 */
export function fetchPurchaseDrillReceipts(filters: {
  beginDate?: string
  endDate?: string
  supplierId?: number
  page?: number
  size?: number
}) {
  return http.get<PageResult<DrillReceiptRow>>('/report/purchase/drill-receipts', filters)
}

/** 下钻库存流水行（变动明细汇总行/周转商品行 → 库存流水） */
export interface DrillInvRecordRow {
  id: number
  goodsId: number
  goodsCode: string | null
  goodsName: string | null
  recordType: string
  changeQty: number
  beforeQty: number | null
  afterQty: number | null
  unitCost: number | null
  bizType: string | null
  bizNo: string | null
  operatorName: string | null
  operateTime: string
  remark: string | null
}

/** 库存流水下钻（BR-RPT-06）：recordType/goodsId 与报表行对应 */
export function fetchInventoryDrillRecords(filters: {
  beginDate?: string
  endDate?: string
  recordType?: string
  goodsId?: number
  page?: number
  size?: number
}) {
  return http.get<PageResult<DrillInvRecordRow>>('/report/inventory/drill-records', filters)
}

/** 下钻损耗单据行：kind=loss 报损单 / kind=check 盘亏差异行 */
export interface DrillLossRow {
  id: number
  orderNo: string
  kind: 'LOSS' | 'CHECK'
  goodsId: number
  goodsName: string
  qty: number
  costPrice: number | null
  /** 金额为成本口径，仅店长及以上可见（BR-RPT-07） */
  amount: number | null
  reason: string | null
  applyName: string | null
  auditorName: string | null
  bizTime: string
}

/** 损耗下钻（BR-RPT-06）：损耗商品行 → 报损/盘亏明细单据 */
export function fetchInventoryDrillLoss(filters: {
  beginDate?: string
  endDate?: string
  kind?: 'loss' | 'check'
  goodsId?: number
  page?: number
  size?: number
}) {
  return http.get<PageResult<DrillLossRow>>('/report/inventory/drill-loss', filters)
}

// =====================================================================
// 通用工具
// =====================================================================

/** 库存状态标签映射（FR-INV-05） */
export const STOCK_STATUS_MAP: Record<string, { label: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
  NORMAL: { label: '正常', type: 'success' },
  OUT: { label: '缺货', type: 'danger' },
  WARNING: { label: '预警', type: 'warning' },
  OVERSTOCK: { label: '积压', type: 'info' },
}

/** 库存变动类型标签映射（§8.2） */
export const CHANGE_TYPE_MAP: Record<string, string> = {
  INIT: '期初建账',
  PURCHASE_IN: '采购入库',
  PURCHASE_RETURN: '采购退货',
  SALE_OUT: '销售出库',
  SALE_RETURN: '销售退货',
  CHECK_SURPLUS: '盘盈',
  CHECK_SHORTAGE: '盘亏',
  LOSS_OUT: '报损',
  OVERFLOW_IN: '报溢',
}

/** 本地时区 YYYY-MM-DD：业务日按门店本地日历归属，用 UTC 会在跨零点时错一天（BR-RPT-02） */
function ymd(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 快捷时间区间（BR-RPT-04：默认今日） */
export function todayRange(): Required<ReportRange> {
  const d = ymd(new Date())
  return { beginDate: d, endDate: d }
}

/** 昨日区间 */
export function yesterdayRange(): Required<ReportRange> {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  const s = ymd(d)
  return { beginDate: s, endDate: s }
}

/** 本周区间（周一至今日） */
export function thisWeekRange(): Required<ReportRange> {
  const now = new Date()
  const day = now.getDay() === 0 ? 7 : now.getDay()
  const monday = new Date(now)
  monday.setDate(now.getDate() - day + 1)
  return { beginDate: ymd(monday), endDate: ymd(now) }
}

/** 本月区间 */
export function thisMonthRange(): Required<ReportRange> {
  const now = new Date()
  const first = new Date(now.getFullYear(), now.getMonth(), 1)
  return { beginDate: ymd(first), endDate: ymd(now) }
}

/** 上月区间 */
export function lastMonthRange(): Required<ReportRange> {
  const now = new Date()
  const first = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  const last = new Date(now.getFullYear(), now.getMonth(), 0)
  return { beginDate: ymd(first), endDate: ymd(last) }
}

/** 近 N 日区间（含今日） */
export function recentDaysRange(days: number): Required<ReportRange> {
  const now = new Date()
  const begin = new Date(now)
  begin.setDate(now.getDate() - days + 1)
  return { beginDate: ymd(begin), endDate: ymd(now) }
}
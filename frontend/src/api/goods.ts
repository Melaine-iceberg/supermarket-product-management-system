import { ElMessage } from 'element-plus'
import { http } from './request'
import type { PageResult } from './request'

// ---------- 分类管理（FR-CAT-01~06） ----------
export interface CategoryNode {
  id: number
  parentId: number
  name: string
  level: number
  sortNo: number
  status: number
  /** 叶子分类（无子分类）才可挂商品（BR-CAT-04） */
  leaf: boolean
  /** 该分类及其子分类下的商品数量（汇总） */
  goodsCount: number
  children: CategoryNode[]
}

export interface CategorySaveForm {
  name: string
  parentId: number
  sortNo: number
  status: number
}

/** 分类树（含各节点子树汇总的商品数量） */
export function getCategoryTree() {
  return http.get<CategoryNode[]>('/categories')
}

export function createCategory(data: CategorySaveForm) {
  return http.post<void>('/categories', data)
}

export function updateCategory(id: number, data: CategorySaveForm) {
  return http.put<void>(`/categories/${id}`, data)
}

/** 停用/启用（FR-CAT-04）：停用级联子孙，启用要求祖先已启用 */
export function updateCategoryStatus(id: number, status: number) {
  return http.put<void>(`/categories/${id}/status`, { status })
}

/** 删除（FR-CAT-05）：仅无子分类且无商品关联时可删 */
export function deleteCategory(id: number) {
  return http.delete<void>(`/categories/${id}`)
}

// ---------- 品牌管理（FR-GOODS-13） ----------
export interface BrandVO {
  id: number
  name: string
  firstLetter: string | null
  logo: string | null
  status: number
  createTime: string
}

/** 品牌列表（商品筛选下拉、品牌管理页共用） */
export function listBrands() {
  return http.get<BrandVO[]>('/brands')
}

/** 品牌新增/编辑请求（FR-GOODS-13）：名称唯一 */
export interface BrandSavePayload {
  name: string
  /** 首字母（拼音，0~2 位英文字母） */
  firstLetter?: string
  logo?: string
  status: number
}

export function createBrand(data: BrandSavePayload) {
  return http.post<void>('/brands', data)
}

export function updateBrand(id: number, data: BrandSavePayload) {
  return http.put<void>(`/brands/${id}`, data)
}

// ---------- 计量单位（FR-GOODS-08） ----------
export interface UnitVO {
  id: number
  name: string
  symbol: string | null
  /** 是否重量单位：称重商品只能选重量单位（BR-GOODS-05） */
  isWeight: number
  status: number
  createTime: string
}

/** 单位列表（商品表单下拉、单位管理页共用） */
export function listUnits() {
  return http.get<UnitVO[]>('/units')
}

/** 单位新增/编辑请求（FR-GOODS-08）：名称唯一；重量属性被商品引用后不可变更 */
export interface UnitSavePayload {
  name: string
  symbol?: string
  isWeight: number
  status: number
}

export function createUnit(data: UnitSavePayload) {
  return http.post<void>('/units', data)
}

export function updateUnit(id: number, data: UnitSavePayload) {
  return http.put<void>(`/units/${id}`, data)
}

// ---------- 商品档案（FR-GOODS-01~07/10~12） ----------
export interface GoodsListVO {
  id: number
  goodsCode: string
  barcode: string | null
  name: string
  categoryId: number
  categoryName: string
  brandId: number | null
  brandName: string | null
  unitId: number
  unitName: string
  spec: string | null
  origin: string | null
  purchasePrice: number
  retailPrice: number
  memberPrice: number
  /** 当前生效促销价（无则为 null，BR-GOODS-04 售价优先级） */
  promoPrice: number | null
  promoEndTime: string | null
  pluCode: string | null
  isWeighed: number
  allowReturn: number
  safetyStockLower: number
  safetyStockUpper: number
  status: number
  /** 库存结存（无库存记录为 0） */
  quantity: number
  costPrice: number
  costAmount: number
  /** NORMAL正常 / WARNING预警 / OUT缺货 / OVERSTOCK积压（FR-INV-05 口径） */
  stockStatus: string
  createTime: string
}

/** 列表查询条件（FR-GOODS-03）：分类含子树；排序白名单 goodsCode/name/quantity/retailPrice/createTime */
export interface GoodsListQuery {
  name?: string
  goodsCode?: string
  barcode?: string
  categoryId?: number
  brandId?: number
  status?: number
  stockStatus?: string
  isWeighed?: number
  allowReturn?: number
  page: number
  size: number
  sortBy?: string
  sortDir?: string
}

/** 商品列表多维检索（FR-GOODS-03） */
export function pageGoods(params: GoodsListQuery) {
  return http.get<PageResult<GoodsListVO>>('/goods', params)
}

/** 停用/启用（FR-GOODS-05）：停用后不可销售/采购，档案永久保留 */
export function updateGoodsStatus(id: number, status: number) {
  return http.put<void>(`/goods/${id}/status`, { status })
}

/** 删除（FR-GOODS-06，BR-GOODS-06）：仅从未发生业务的商品可删 */
export function deleteGoods(id: number) {
  return http.delete<void>(`/goods/${id}`)
}

/** 清单导出（FR-GOODS-12）：与列表同一筛选条件，返回 xlsx blob */
export function exportGoods(params: Omit<GoodsListQuery, 'page' | 'size'>) {
  return http.download('/goods/export', params)
}

/** 商品档案完整记录（详情接口内嵌，含列表没有的 remark/image 等） */
export interface GoodsRecord {
  id: number
  goodsCode: string
  barcode: string | null
  name: string
  categoryId: number
  brandId: number | null
  unitId: number
  spec: string | null
  origin: string | null
  purchasePrice: number
  retailPrice: number
  memberPrice: number
  pluCode: string | null
  isWeighed: number
  allowReturn: number
  safetyStockLower: number
  safetyStockUpper: number | null
  image: string | null
  remark: string | null
  status: number
  createTime?: string
  updateTime?: string
}

/** 库存结存（详情接口内嵌，无结存行时为 null） */
export interface GoodsInventoryVO {
  goodsId: number
  quantity: number
  costPrice: number
  costAmount: number
  lastChangeTime: string | null
}

/** 库存流水行（详情接口最近 10 条） */
export interface GoodsInventoryRecordVO {
  id: number
  goodsId: number
  /** INIT/PURCHASE_IN/PURCHASE_RETURN/SALE_OUT/SALE_RETURN/CHECK_SURPLUS/CHECK_SHORTAGE/LOSS_OUT/OVERFLOW_IN */
  recordType: string
  changeQty: number
  beforeQty: number
  afterQty: number
  unitCost: number
  /** 关联单据类型 PO/RK/PT/XS/XT/PD/SZ/BY */
  bizType: string | null
  bizNo: string | null
  operatorName: string | null
  operateTime: string
  remark: string | null
}

/** 最近采购价行（来源入库单） */
export interface GoodsPurchasePriceVO {
  orderNo: string
  receiptTime: string
  price: number
  quantity: number
}

/** 近 30 天销量销售额 */
export interface GoodsSalesSummaryVO {
  totalQuantity: number | null
  totalAmount: number | null
}

/** 关联供应商行 */
export interface GoodsSupplierBriefVO {
  supplierId: number
  supplierName: string
  supplyPrice: number | null
  isDefault: number
  lastSupplyTime: string | null
}

/** 商品详情聚合（FR-GOODS-04）：档案 + 当前库存成本 + 流水/采购价/销量/供应商/生效促销 */
export interface GoodsDetailVO {
  goods: GoodsRecord
  categoryName: string
  unitName: string
  brandName: string | null
  inventory: GoodsInventoryVO | null
  activePromotion: PromotionVO | null
  recentInventoryRecords: GoodsInventoryRecordVO[]
  recentPurchasePrices: GoodsPurchasePriceVO[]
  salesSummary30d: GoodsSalesSummaryVO
  suppliers: GoodsSupplierBriefVO[]
}

/** 商品详情（FR-GOODS-04）：档案 + 库存成本 + 流水/采购价/销量/供应商聚合 */
export function getGoodsDetail(id: number) {
  return http.get<GoodsDetailVO>(`/goods/${id}/detail`)
}

// ---------- 单品促销（FR-GOODS-09） ----------

/** 促销记录（后端按实体直出） */
export interface PromotionVO {
  id: number
  goodsId: number
  promoPrice: number
  startTime: string
  endTime: string
  /** ACTIVE生效 / EXPIRED过期 / DISABLED停用 */
  status: 'ACTIVE' | 'EXPIRED' | 'DISABLED'
  createBy: string | null
  createTime: string | null
  updateBy: string | null
  updateTime: string | null
  remark: string | null
}

/** 促销设置请求（FR-GOODS-09）：单个或批量；同商品同时间段只允许一条生效促销（BR-GOODS-04） */
export interface PromotionCreatePayload {
  goodsIds: number[]
  promoPrice: number
  startTime: string
  endTime: string
  remark?: string
}

/** 设置促销（单个/批量，时间格式 yyyy-MM-dd HH:mm:ss） */
export function createPromotion(data: PromotionCreatePayload) {
  return http.post<void>('/promotions', data)
}

/** 停用促销：仅 ACTIVE 可停用，非 ACTIVE 幂等返回 */
export function disablePromotion(id: number) {
  return http.put<void>(`/promotions/${id}/disable`)
}

/** 某商品的促销记录（含历史，时间倒序） */
export function listPromotionsByGoods(goodsId: number) {
  return http.get<PromotionVO[]>('/promotions', { goodsId })
}

// ---------- 调价历史（FR-GOODS-10） ----------

export interface PriceHistoryVO {
  id: number
  goodsId: number
  /** RETAIL零售 / MEMBER会员 / PURCHASE进货 */
  priceType: 'RETAIL' | 'MEMBER' | 'PURCHASE'
  /** 前值（新设价格时为 null） */
  oldValue: number | null
  /** 后值（清空价格时为 null） */
  newValue: number | null
  effectiveTime: string | null
  operatorName: string | null
  reason: string | null
  createTime: string
}

/** 调价轨迹（FR-GOODS-10）：按商品查询，时间倒序，最多 500 条 */
export function getGoodsPriceHistory(id: number) {
  return http.get<PriceHistoryVO[]>(`/goods/${id}/price-history`)
}

/** 新增商品请求（FR-GOODS-01）：编码/条码留空自动生成 */
export interface GoodsCreatePayload {
  name: string
  categoryId: number
  unitId: number
  brandId?: number
  spec?: string
  origin?: string
  purchasePrice?: number
  retailPrice: number
  memberPrice?: number
  barcode?: string
  goodsCode?: string
  pluCode?: string
  isWeighed: number
  allowReturn: number
  safetyStockLower?: number
  safetyStockUpper?: number
  /** >0 时生成"期初建账"流水（BR-INV-01），仅创建时可设 */
  initialStock?: number
  remark?: string
}

/** 编辑商品请求（FR-GOODS-02）：编码/初始库存不可改；条码变更须 barcodeConfirmed */
export interface GoodsUpdatePayload extends Omit<GoodsCreatePayload, 'goodsCode' | 'initialStock'> {
  barcode: string
  /** 条码修改二次确认标记（FR-GOODS-02） */
  barcodeConfirmed: boolean
  /** 图片路径（编辑全量覆盖，透传原值） */
  image?: string
  /** 调价原因（价格变更时随调价历史记录，FR-GOODS-10） */
  priceChangeReason?: string
}

/** 新增结果：回显系统生成的编码与店内码 */
export interface GoodsCreatedVO {
  id: number
  goodsCode: string
  barcode: string
}

/** 新增商品（FR-GOODS-01） */
export function createGoods(data: GoodsCreatePayload) {
  return http.post<GoodsCreatedVO>('/goods', data)
}

/** 编辑商品（FR-GOODS-02）：调价留痕 */
export function updateGoods(id: number, data: GoodsUpdatePayload) {
  return http.put<void>(`/goods/${id}`, data)
}

/** 店内码预览（FR-GOODS-07）：EAN-13 前缀20~29 + 序号 + 校验位，不落库 */
export function nextBarcode() {
  return http.get<{ barcode: string }>('/goods/next-barcode')
}

// ---------- Excel 批量导入（FR-GOODS-11，校验—确认—部分提交） ----------

/** 导入单行校验结果：行号、是否通过、失败原因与原始行数据（列名 → 单元格文本） */
export interface GoodsImportRowVO {
  /** Excel 行号（从 1 开始） */
  rowNum: number
  passed: boolean
  errors: string[]
  data: Record<string, string>
}

export interface GoodsImportReportVO {
  total: number
  passed: number
  failed: number
  rows: GoodsImportRowVO[]
}

export interface GoodsImportResultVO {
  imported: number
  failed: number
  failedRows: GoodsImportRowVO[]
}

/** 下载导入模板 */
export function downloadGoodsTemplate() {
  return http.download('/goods/import/template')
}

/** 上传校验：全量校验生成报告，不写库（BR-GOODS-07） */
export function validateGoodsImport(file: File, onProgress?: (percent: number) => void) {
  return http.upload<GoodsImportReportVO>('/goods/import/validate', file, 'file', onProgress)
}

/** 确认提交：单事务内仅导入校验通过的行，失败行返回明细供修正重传 */
export function commitGoodsImport(file: File, onProgress?: (percent: number) => void) {
  return http.upload<GoodsImportResultVO>('/goods/import/commit', file, 'file', onProgress)
}

/** 失败行导出：重新上传同一文件，返回仅含失败行的 xlsx（末列附失败原因，修正后可直接重传） */
export async function exportGoodsFailedRows(file: File) {
  const blob = await http.postDownload('/goods/import/failed-export', file)
  if (blob.type.includes('application/json')) {
    const body = JSON.parse(await blob.text()) as { message?: string }
    ElMessage.error(body.message || '失败行导出失败')
    throw new Error(body.message || '失败行导出失败')
  }
  return blob
}

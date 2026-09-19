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

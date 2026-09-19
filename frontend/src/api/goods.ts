import { http } from './request'

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

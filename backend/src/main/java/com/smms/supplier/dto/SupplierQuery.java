package com.smms.supplier.dto;

/**
 * 供应商列表查询（FR-SUP-02）：名称/编码/联系人/电话模糊，状态精确，
 * 供货品类（该分类下存在供货商品）筛选。
 */
public record SupplierQuery(
        String name,
        String code,
        String contactPerson,
        String phone,
        Integer status,
        /** 供货品类：该分类下存在关联商品的供应商（FR-SUP-02） */
        Long categoryId,
        int page,
        int size
) {
}

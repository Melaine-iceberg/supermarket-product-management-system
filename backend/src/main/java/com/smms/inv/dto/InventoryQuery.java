package com.smms.inv.dto;

/**
 * 实时库存查询条件（FR-INV-01）：分类（含子树）/名称/编码/库存状态组合筛选。
 */
public record InventoryQuery(
        String name,
        String goodsCode,
        Long categoryId,
        /** 库存状态：OUT缺货/WARNING预警/OVERSTOCK积压/NORMAL正常（FR-INV-05） */
        String stockStatus,
        int page,
        int size,
        /** 排序字段白名单：goodsCode/name/quantity/costAmount/lastChangeTime */
        String sortBy,
        String sortDir
) {
}

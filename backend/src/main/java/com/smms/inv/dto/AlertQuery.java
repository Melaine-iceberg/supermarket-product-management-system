package com.smms.inv.dto;

/**
 * 库存预警查询条件（FR-INV-05）：名称/编码/分类（含子树）筛选，
 * alertType 为空时返回全部预警（缺货+预警+积压）。
 */
public record AlertQuery(
        String name,
        String goodsCode,
        Long categoryId,
        /** 预警类型：OUT缺货/WARNING预警/OVERSTOCK积压；空=全部 */
        String alertType,
        int page,
        int size,
        /** 排序字段白名单：gap缺口量(默认)/quantity/goodsCode/name/lastChangeTime */
        String sortBy,
        String sortDir
) {
}

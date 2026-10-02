package com.smms.rpt.dto;

import java.math.BigDecimal;

/**
 * 库存汇总表行（FR-RPT-04①）：分类/商品维度——结存数量、成本单价、
 * 库存成本金额、零售价值、库存状态。分类维度下 goodsId/goodsName 为空。
 */
public record InvSummaryRow(
        Long categoryId,
        String categoryName,
        Long goodsId,
        String goodsCode,
        String goodsName,
        long goodsCount,
        BigDecimal stockQty,
        BigDecimal costPrice,
        BigDecimal costAmount,
        BigDecimal retailValue,
        /** NORMAL正常/OUT缺货/WARNING预警/OVERSTOCK积压（FR-INV-05） */
        String stockStatus) {
}

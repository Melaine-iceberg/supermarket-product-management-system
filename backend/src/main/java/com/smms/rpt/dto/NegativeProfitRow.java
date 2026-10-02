package com.smms.rpt.dto;

import java.math.BigDecimal;

/**
 * 负毛利商品清单（FR-RPT-05）：成交价低于成本价的明细聚合，
 * 用于发现定价错误与异常改价。
 */
public record NegativeProfitRow(
        Long goodsId,
        String goodsCode,
        String goodsName,
        String categoryName,
        BigDecimal unitPrice,
        BigDecimal costPrice,
        BigDecimal unitProfit,
        BigDecimal qty,
        long orderCount,
        BigDecimal paidAmount,
        BigDecimal costAmount,
        BigDecimal profitAmount) {
}

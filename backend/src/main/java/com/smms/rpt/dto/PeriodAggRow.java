package com.smms.rpt.dto;

import java.math.BigDecimal;

/**
 * 区间聚合原始值（BR-RPT-01 口径的原始输入），派生指标由 {@link SalesMetrics} 统一计算。
 */
public record PeriodAggRow(
        long orderCount,
        Long itemCount,
        BigDecimal salesAmount,
        BigDecimal discountAmount,
        BigDecimal netSalesAmount,
        BigDecimal costAmount,
        BigDecimal refundAmount,
        BigDecimal returnCostAmount) {
}

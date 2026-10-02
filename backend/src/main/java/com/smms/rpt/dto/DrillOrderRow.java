package com.smms.rpt.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 下钻销售单行（BR-RPT-06）：逐单金额之和与汇总一致（AC-16 验收要点）。
 */
public record DrillOrderRow(
        Long id,
        String orderNo,
        String cashierName,
        Integer counterNo,
        String memberName,
        LocalDateTime saleTime,
        LocalDate businessDate,
        int itemCount,
        BigDecimal goodsAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        BigDecimal paidAmount,
        BigDecimal costAmount,
        BigDecimal profitAmount,
        String returnStatus) {
}

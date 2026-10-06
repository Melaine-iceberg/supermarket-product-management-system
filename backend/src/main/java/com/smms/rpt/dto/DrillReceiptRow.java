package com.smms.rpt.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 下钻入库单行（BR-RPT-06）：按期 = 实收日期 ≤ 预计到货日期（与到货及时率同口径）。
 */
public record DrillReceiptRow(
        Long id,
        String receiptNo,
        Long purchaseOrderId,
        String purchaseOrderNo,
        Long supplierId,
        String supplierName,
        LocalDateTime receiptTime,
        String operatorName,
        String deliveryOrderNo,
        BigDecimal totalAmount,
        LocalDate expectDate,
        Boolean onTime) {
}

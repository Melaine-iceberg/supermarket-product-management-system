package com.smms.rpt.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 下钻采购单行（BR-RPT-06）：逐单金额之和与采购汇总一致（AC-16 验收要点）。
 */
public record DrillPurchaseRow(
        Long id,
        String orderNo,
        Long supplierId,
        String supplierName,
        String status,
        String createBy,
        LocalDateTime createTime,
        LocalDateTime auditTime,
        LocalDate expectDate,
        BigDecimal totalAmount,
        BigDecimal receivedAmount,
        BigDecimal returnAmount,
        String settleStatus,
        BigDecimal settleAmount) {
}

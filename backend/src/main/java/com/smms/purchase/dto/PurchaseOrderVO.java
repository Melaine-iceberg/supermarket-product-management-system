package com.smms.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 采购单列表行（FR-PUR-03）：展示总额、已入库数量、入库进度、结算状态。
 */
public record PurchaseOrderVO(
        Long id,
        String orderNo,
        Long supplierId,
        String supplierName,
        String status,
        String createBy,
        LocalDateTime createTime,
        LocalDateTime submitTime,
        String auditBy,
        LocalDateTime auditTime,
        LocalDate expectDate,
        BigDecimal totalAmount,
        BigDecimal receivedAmount,
        BigDecimal returnAmount,
        BigDecimal orderedQty,
        BigDecimal receivedQty,
        /** 入库进度 = 已入库数量 / 订购数量（百分比，订购为 0 时为 0） */
        Integer receiveProgress,
        String settleStatus,
        BigDecimal settleAmount,
        String terminateReason,
        String remark
) {
}

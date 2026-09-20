package com.smms.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退货单列表行（FR-SALE-10）。
 */
public record ReturnOrderVO(
        Long id,
        String orderNo,
        Long salesOrderId,
        String salesOrderNo,
        String memberName,
        String memberPhone,
        BigDecimal refundAmount,
        String returnReason,
        String refundType,
        String cashierName,
        String authorizerName,
        LocalDateTime returnTime
) {
}

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

    /** 返回手机号替换为指定值的新实例（BR-MEM-08 按角色脱敏） */
    public ReturnOrderVO withMemberPhone(String maskedPhone) {
        return new ReturnOrderVO(id, orderNo, salesOrderId, salesOrderNo, memberName, maskedPhone,
                refundAmount, returnReason, refundType, cashierName, authorizerName, returnTime);
    }
}

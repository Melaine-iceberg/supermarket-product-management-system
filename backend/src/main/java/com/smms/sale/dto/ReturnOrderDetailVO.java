package com.smms.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 退货单详情（FR-SALE-10）：单头 + 明细（按原成交单价退款、成本快照回冲）。
 */
public record ReturnOrderDetailVO(
        Long id,
        String orderNo,
        Long salesOrderId,
        String salesOrderNo,
        LocalDateTime salesTime,
        String memberName,
        String memberPhone,
        String returnReason,
        String reasonRemark,
        BigDecimal refundAmount,
        String refundType,
        String cashierName,
        String authorizerName,
        LocalDateTime authorizeTime,
        LocalDateTime returnTime,
        LocalDate businessDate,
        List<ItemVO> items
) {

    /** 退货明细（DR-20） */
    public record ItemVO(
            Long id,
            Long salesItemId,
            Long goodsId,
            String goodsName,
            String barcode,
            BigDecimal returnQty,
            BigDecimal refundPrice,
            BigDecimal refundAmount,
            BigDecimal costPrice,
            BigDecimal costAmount,
            String remark
    ) {
    }
}

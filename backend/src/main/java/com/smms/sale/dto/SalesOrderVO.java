package com.smms.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 销售单列表行（FR-SALE-09）。
 */
public record SalesOrderVO(
        Long id,
        String orderNo,
        Integer counterNo,
        String cashierName,
        String memberName,
        String memberPhone,
        LocalDateTime saleTime,
        Integer itemCount,
        BigDecimal goodsAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        BigDecimal paidAmount,
        BigDecimal changeAmount,
        /** 退货状态：NONE/PARTIAL/ALL */
        String returnStatus,
        /** 累计退款金额 */
        BigDecimal refundAmount
) {
}

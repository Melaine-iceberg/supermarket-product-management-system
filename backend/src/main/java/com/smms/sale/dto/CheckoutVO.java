package com.smms.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 结算结果（FR-SALE-06）：单号与找零回显，供小票预览打印（FR-RPT-09）。
 */
public record CheckoutVO(
        Long orderId,
        String orderNo,
        Integer itemCount,
        BigDecimal goodsAmount,
        BigDecimal memberDiscountAmount,
        BigDecimal orderDiscountAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        BigDecimal paidAmount,
        BigDecimal changeAmount,
        List<PaymentLineVO> payments,
        LocalDateTime saleTime
) {

    /** 收款回显 */
    public record PaymentLineVO(String payType, BigDecimal amount) {
    }
}

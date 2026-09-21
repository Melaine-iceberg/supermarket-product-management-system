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

    /** 返回手机号替换为指定值的新实例（BR-MEM-08 按角色脱敏） */
    public SalesOrderVO withMemberPhone(String maskedPhone) {
        return new SalesOrderVO(id, orderNo, counterNo, cashierName, memberName, maskedPhone,
                saleTime, itemCount, goodsAmount, discountAmount, payableAmount, paidAmount,
                changeAmount, returnStatus, refundAmount);
    }
}

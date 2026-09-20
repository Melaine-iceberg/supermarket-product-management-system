package com.smms.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 班次视图（FR-SALE-12）：列表行与交班单主体，供打印核对（FR-RPT-09）。
 */
public record ShiftVO(
        Long id,
        Long cashierId,
        String cashierName,
        Integer counterNo,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Integer orderCount,
        /** 净销售 = Σ应收 − 退货额 */
        BigDecimal salesAmount,
        BigDecimal cashAmount,
        BigDecimal bankAmount,
        BigDecimal mobileAmount,
        BigDecimal balanceAmount,
        BigDecimal returnAmount,
        /** 应收现金 = 现金收款 − 现金退款 */
        BigDecimal expectedCash,
        String status
) {

    /** 班次详情（FR-SALE-12 交班单打印视图）：班次 + 该班次销售单摘要列表 */
    public record Detail(ShiftVO shift, List<OrderLine> orders) {
    }

    /** 班次内销售单摘要行 */
    public record OrderLine(
            Long id,
            String orderNo,
            LocalDateTime saleTime,
            Integer itemCount,
            BigDecimal payableAmount,
            BigDecimal paidAmount,
            BigDecimal changeAmount,
            String returnStatus,
            BigDecimal refundAmount
    ) {
    }
}

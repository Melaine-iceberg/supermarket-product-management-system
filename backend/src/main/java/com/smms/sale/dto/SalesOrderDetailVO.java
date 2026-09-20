package com.smms.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售单详情（FR-SALE-09）：单头 + 明细快照（成交单价/数量/折扣/优惠/成本快照/毛利额）
 * + 收款信息 + 关联退货记录。
 */
public record SalesOrderDetailVO(
        Long id,
        String orderNo,
        Integer counterNo,
        String cashierName,
        Long memberId,
        String memberName,
        String memberPhone,
        LocalDateTime saleTime,
        LocalDate businessDate,
        Integer itemCount,
        BigDecimal goodsAmount,
        BigDecimal memberDiscountAmount,
        BigDecimal orderDiscountAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        BigDecimal paidAmount,
        BigDecimal changeAmount,
        BigDecimal orderDiscountRate,
        BigDecimal costAmount,
        BigDecimal profitAmount,
        String returnStatus,
        String authorizerName,
        String remark,
        List<ItemVO> items,
        List<PaymentVO> payments,
        List<LinkedReturnVO> returns
) {

    /** 销售明细快照（BR-SALE-04） */
    public record ItemVO(
            Long id,
            Long goodsId,
            String goodsName,
            String barcode,
            BigDecimal unitPrice,
            BigDecimal quantity,
            Integer isWeighed,
            BigDecimal netWeight,
            BigDecimal amount,
            BigDecimal discountRate,
            BigDecimal discountAmount,
            BigDecimal paidAmount,
            BigDecimal costPrice,
            BigDecimal costAmount,
            BigDecimal profitAmount,
            BigDecimal returnedQty,
            String lineType,
            String remark
    ) {
    }

    /** 收款记录（FR-SALE-06） */
    public record PaymentVO(
            String payType,
            BigDecimal amount,
            LocalDateTime payTime,
            String operatorName
    ) {
    }

    /** 关联退货单摘要（FR-SALE-10） */
    public record LinkedReturnVO(
            Long id,
            String orderNo,
            BigDecimal refundAmount,
            String returnReason,
            String refundType,
            String cashierName,
            String authorizerName,
            LocalDateTime returnTime
    ) {
    }
}

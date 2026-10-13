package com.smms.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售单详情（FR-SALE-09）：单头 + 明细快照（成交单价/数量/折扣/优惠/成本快照/毛利额）
 * + 收款信息 + 关联退货记录 + 操作日志（GAP-P2-03）。
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
        String discountReason,
        BigDecimal costAmount,
        BigDecimal profitAmount,
        String returnStatus,
        String authorizerName,
        String remark,
        List<ItemVO> items,
        List<PaymentVO> payments,
        List<LinkedReturnVO> returns,
        /** 单据操作日志（结算/退货等，按时间升序；GAP-P2-03） */
        List<OpLogVO> operationLogs
) {

    /** 销售明细快照（BR-SALE-04） */
    public record ItemVO(
            Long id,
            Long goodsId,
            String goodsName,
            String barcode,
            BigDecimal unitPrice,
            BigDecimal originalUnitPrice,
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
            String remark,
            String priceReason,
            /** 商品档案"是否允许退货"（1 是 0 否；无码商品/档案已删为空；BR-SALE-07） */
            Integer allowReturn
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

    /** 单据操作日志行（FR-SALE-09：详情操作日志区；GAP-P2-03） */
    public record OpLogVO(
            Long id,
            String action,
            String username,
            String realName,
            /** 1 成功 0 失败 */
            Integer result,
            String errorMsg,
            LocalDateTime operateTime
    ) {
    }
}

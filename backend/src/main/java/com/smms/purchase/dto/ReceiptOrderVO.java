package com.smms.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库单列表/详情视图（FR-PUR-07）：详情含逐行实收数量、实收单价、金额、验收备注，
 * 供 A4 打印视图使用（含供应商、明细、合计、验收人、时间）。
 */
public record ReceiptOrderVO(
        Long id,
        String orderNo,
        Long purchaseOrderId,
        String purchaseOrderNo,
        Long supplierId,
        String supplierName,
        LocalDateTime receiptTime,
        String operatorName,
        String authorizerName,
        String deliveryOrderNo,
        BigDecimal totalAmount,
        String remark,
        List<ItemVO> items
) {

    public record ItemVO(
            Long id,
            Long purchaseItemId,
            Long goodsId,
            String goodsCode,
            String goodsName,
            String spec,
            String unitName,
            BigDecimal receivedQty,
            BigDecimal receivedPrice,
            BigDecimal amount,
            String remark
    ) {
    }
}

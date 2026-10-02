package com.smms.rpt.dto;

import java.math.BigDecimal;

/**
 * 采购汇总行（FR-RPT-06①②）：供应商/商品维度——采购数量、采购金额、
 * 已入库、在途、退货、结算状态；供应商维度含占比。
 */
public record PurchSummaryRow(
        Long supplierId,
        String supplierName,
        Long goodsId,
        String goodsCode,
        String goodsName,
        String categoryName,
        long orderCount,
        BigDecimal purchaseQty,
        BigDecimal purchaseAmount,
        BigDecimal receivedQty,
        BigDecimal inTransitQty,
        BigDecimal returnQty,
        BigDecimal returnAmount,
        BigDecimal settledAmount,
        BigDecimal partialSettledAmount,
        /** 占比（仅供应商排行视图填充） */
        BigDecimal shareRate) {
}

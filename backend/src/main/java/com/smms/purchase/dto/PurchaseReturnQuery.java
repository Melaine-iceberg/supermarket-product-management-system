package com.smms.purchase.dto;

import java.time.LocalDate;

/**
 * 采购退货单查询（FR-PUR-08）：退货单号/采购单号/供应商/状态/日期区间。
 */
public record PurchaseReturnQuery(
        String orderNo,
        String purchaseOrderNo,
        Long supplierId,
        String status,
        LocalDate beginDate,
        LocalDate endDate,
        int page,
        int size
) {
}

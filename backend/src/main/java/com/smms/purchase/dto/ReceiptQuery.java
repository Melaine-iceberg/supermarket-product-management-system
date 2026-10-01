package com.smms.purchase.dto;

import java.time.LocalDate;

/**
 * 入库单查询（FR-PUR-07）：入库单号/采购单号/供应商/日期区间/操作人。
 */
public record ReceiptQuery(
        String orderNo,
        String purchaseOrderNo,
        Long supplierId,
        LocalDate beginDate,
        LocalDate endDate,
        String operatorName,
        int page,
        int size
) {
}

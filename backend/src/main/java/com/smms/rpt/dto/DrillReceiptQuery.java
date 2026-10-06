package com.smms.rpt.dto;

import java.time.LocalDate;

/**
 * 到货及时率下钻查询（BR-RPT-06）：供应商行 → 构成统计的入库单列表。
 */
public record DrillReceiptQuery(
        LocalDate beginDate,
        LocalDate endDate,
        Long supplierId,
        int page,
        int size) {

    public DrillReceiptQuery {
        page = Math.max(page, 1);
        size = size <= 0 ? 20 : Math.min(size, 100);
    }
}

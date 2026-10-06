package com.smms.rpt.dto;

import java.time.LocalDate;

/**
 * 采购汇总下钻查询（BR-RPT-06）：供应商/商品维度汇总行
 * → 构成它的采购单列表（可再打开采购单详情）。
 */
public record DrillPurchaseQuery(
        LocalDate beginDate,
        LocalDate endDate,
        Long supplierId,
        Long goodsId,
        int page,
        int size) {

    public DrillPurchaseQuery {
        page = Math.max(page, 1);
        size = size <= 0 ? 20 : Math.min(size, 100);
    }
}

package com.smms.rpt.dto;

import java.time.LocalDate;

/**
 * 损耗报表下钻查询（BR-RPT-06）：商品行 → 损耗明细单据。
 * kind=loss 报损单（loss_order 已审核）；kind=check 盘亏明细（已审核盘点单差异行）。
 */
public record DrillLossQuery(
        LocalDate beginDate,
        LocalDate endDate,
        String kind,
        Long goodsId,
        int page,
        int size) {

    public DrillLossQuery {
        page = Math.max(page, 1);
        size = size <= 0 ? 20 : Math.min(size, 100);
    }
}

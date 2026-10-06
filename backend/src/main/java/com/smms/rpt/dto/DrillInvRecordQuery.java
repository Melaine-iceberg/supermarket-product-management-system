package com.smms.rpt.dto;

import java.time.LocalDate;

/**
 * 库存变动明细下钻查询（BR-RPT-06）：变动类型/商品行
 * → 构成该汇总的库存流水列表（流水关联单据号可回溯来源单据）。
 */
public record DrillInvRecordQuery(
        LocalDate beginDate,
        LocalDate endDate,
        String recordType,
        Long goodsId,
        int page,
        int size) {

    public DrillInvRecordQuery {
        page = Math.max(page, 1);
        size = size <= 0 ? 20 : Math.min(size, 100);
    }
}

package com.smms.inv.dto;

import java.time.LocalDate;

/**
 * 库存流水查询条件（FR-INV-03）：按商品/类型/时间区间/单据号/操作人组合查询。
 * 日期为闭区间 [beginDate 00:00, endDate 24:00)。
 */
public record InventoryRecordQuery(
        Long goodsId,
        String goodsName,
        /** 变动类型：INIT/PURCHASE_IN/PURCHASE_RETURN/SALE_OUT/SALE_RETURN/CHECK_SURPLUS/CHECK_SHORTAGE/LOSS_OUT/OVERFLOW_IN */
        String recordType,
        String bizNo,
        String operatorName,
        LocalDate beginDate,
        LocalDate endDate,
        int page,
        int size
) {
}

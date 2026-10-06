package com.smms.rpt.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 下钻库存流水行（BR-RPT-06）：变动数量与金额之和与变动明细汇总一致。
 */
public record DrillInvRecordRow(
        Long id,
        Long goodsId,
        String goodsCode,
        String goodsName,
        String recordType,
        BigDecimal changeQty,
        BigDecimal beforeQty,
        BigDecimal afterQty,
        BigDecimal unitCost,
        String bizType,
        String bizNo,
        String operatorName,
        LocalDateTime operateTime,
        String remark) {
}

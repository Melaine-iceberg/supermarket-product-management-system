package com.smms.inv.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存流水列表行（FR-INV-03）：不可变流水的完整展示字段，
 * 变动数量带正负号（入库 + / 出库 -，BR-INV-05）。
 */
public record InventoryRecordVO(
        Long id,
        Long goodsId,
        String goodsCode,
        String goodsName,
        String unitName,
        String recordType,
        BigDecimal changeQty,
        BigDecimal beforeQty,
        BigDecimal afterQty,
        BigDecimal unitCost,
        String bizType,
        String bizNo,
        String operatorName,
        LocalDateTime operateTime,
        String remark
) {
}

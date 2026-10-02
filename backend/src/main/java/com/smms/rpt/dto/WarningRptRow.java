package com.smms.rpt.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存预警报表行（FR-RPT-04②）：缺货/低库存/积压清单，含建议采购量。
 */
public record WarningRptRow(
        Long goodsId,
        String goodsCode,
        String goodsName,
        String categoryName,
        /** OUT缺货/WARNING预警/OVERSTOCK积压 */
        String alertType,
        BigDecimal stockQty,
        BigDecimal safetyStockLower,
        BigDecimal safetyStockUpper,
        /** 缺口量：缺货/预警 = 下限−结存；积压 = 结存−上限 */
        BigDecimal gapQty,
        /** 建议采购量（BR-INV-07；积压为空） */
        BigDecimal suggestQty,
        LocalDateTime lastChangeTime) {
}

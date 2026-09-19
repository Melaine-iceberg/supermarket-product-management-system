package com.smms.inv.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存预警列表行（FR-INV-05）：结存与安全库存上下限判定出的预警商品。
 * 缺口量：缺货/预警 = 下限 − 结存（负库存时含欠账）；积压 = 结存 − 上限。
 */
public record AlertItemVO(
        Long goodsId,
        String goodsCode,
        String barcode,
        String name,
        Long categoryId,
        String categoryName,
        String unitName,
        String spec,
        Integer isWeighed,
        BigDecimal quantity,
        BigDecimal safetyStockLower,
        BigDecimal safetyStockUpper,
        /** OUT缺货/WARNING预警/OVERSTOCK积压（FR-INV-05） */
        String alertType,
        /** 缺口量（缺货/预警为补货缺口，积压为超储量），排序与导出口径 */
        BigDecimal gapQty,
        LocalDateTime lastChangeTime
) {
}

package com.smms.inv.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 实时库存列表行（FR-INV-01）：商品编码、名称、分类、单位、结存数量、
 * 成本单价、库存成本金额、零售价、库存状态、最近变动时间。
 * 无结存行的商品按 0 结存展示（建档时初始库存为 0 不生成结存行）。
 */
public record InventoryItemVO(
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
        BigDecimal costPrice,
        BigDecimal costAmount,
        BigDecimal retailPrice,
        BigDecimal safetyStockLower,
        BigDecimal safetyStockUpper,
        /** OUT/WARNING/OVERSTOCK/NORMAL（FR-INV-05） */
        String stockStatus,
        LocalDateTime lastChangeTime
) {
}

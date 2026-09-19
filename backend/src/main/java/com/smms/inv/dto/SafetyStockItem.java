package com.smms.inv.dto;

import java.math.BigDecimal;

/**
 * 单商品安全库存阈值设置项（FR-INV-06）：
 * lower 必填；upper 为 null 表示不设上限（清除积压预警）。
 */
public record SafetyStockItem(
        Long goodsId,
        BigDecimal safetyStockLower,
        BigDecimal safetyStockUpper
) {
}

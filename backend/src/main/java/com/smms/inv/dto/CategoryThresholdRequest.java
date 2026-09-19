package com.smms.inv.dto;

import java.math.BigDecimal;

/**
 * 按分类批量设置默认阈值请求（FR-INV-06）：
 * 作用于该分类（含子分类）下全部商品；lower 必填，upper 为 null 表示不设上限。
 */
public record CategoryThresholdRequest(
        Long categoryId,
        BigDecimal safetyStockLower,
        BigDecimal safetyStockUpper
) {
}

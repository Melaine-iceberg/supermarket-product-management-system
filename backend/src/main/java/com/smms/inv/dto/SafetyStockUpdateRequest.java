package com.smms.inv.dto;

import java.util.List;

/**
 * 安全库存阈值维护请求（FR-INV-06）：单个或批量设置，
 * 每项提供目标阈值全量（lower + upper/null），修改逐商品留痕。
 */
public record SafetyStockUpdateRequest(
        List<SafetyStockItem> items
) {
}

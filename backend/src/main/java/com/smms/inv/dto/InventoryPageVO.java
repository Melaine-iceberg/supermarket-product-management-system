package com.smms.inv.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 实时库存分页结果（FR-INV-01）：total 即合计行的总 SKU 数，
 * totalCostAmount 为当前筛选口径下的库存总成本金额。
 */
public record InventoryPageVO(
        List<InventoryItemVO> list,
        long total,
        BigDecimal totalCostAmount
) {
}

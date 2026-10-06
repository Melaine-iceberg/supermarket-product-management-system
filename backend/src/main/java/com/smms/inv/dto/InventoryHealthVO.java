package com.smms.inv.dto;

import java.util.List;

/**
 * 库存体检结果（FR-INV-11）：差异清单分页 + 参与校验商品数；
 * total 即差异商品数（AC-04 通过标准：差异清单为空）。
 */
public record InventoryHealthVO(
        List<InventoryHealthRow> rows,
        long total,
        long checkedSkus) {
}

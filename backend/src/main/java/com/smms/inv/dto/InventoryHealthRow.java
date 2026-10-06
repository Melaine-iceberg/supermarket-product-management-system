package com.smms.inv.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存体检差异行（FR-INV-11）：账面结存（inventory 结存字段）
 * 与理论结存（Σ全部流水 change_qty，含 INIT 期初建账）不一致的商品。
 */
public record InventoryHealthRow(
        Long goodsId,
        String goodsCode,
        String goodsName,
        String categoryName,
        /** 账面结存（结存行缺失按 0 处理，与实时库存列表口径一致） */
        BigDecimal bookQty,
        /** 理论结存 = 期初 + Σ流水数量（BR-INV-05/08） */
        BigDecimal recordQty,
        /** 差异 = 账面 − 理论 */
        BigDecimal diffQty,
        BigDecimal costPrice,
        LocalDateTime lastChangeTime,
        /** 结存行缺失（理论结存≠0 但 inventory 无行，修正时自动补行） */
        boolean rowMissing) {
}

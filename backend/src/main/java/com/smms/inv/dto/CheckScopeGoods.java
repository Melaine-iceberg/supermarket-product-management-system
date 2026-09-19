package com.smms.inv.dto;

import java.math.BigDecimal;

/**
 * 盘点范围解析结果行（FR-INV-07）：创建盘点单时按范围圈定商品，
 * 并快照当前账面数量与成本单价（基准时间 = 单据创建时刻，BR-INV-10）。
 */
public record CheckScopeGoods(
        Long goodsId,
        BigDecimal bookQty,
        BigDecimal costPrice) {
}

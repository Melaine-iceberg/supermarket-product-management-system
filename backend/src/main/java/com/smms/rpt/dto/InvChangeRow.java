package com.smms.rpt.dto;

import java.math.BigDecimal;

/**
 * 库存变动明细汇总行（FR-RPT-04③）：按变动类型汇总数量与金额。
 * changeQty 带符号（入库 +，出库 −），金额 = Σ|数量|×单位成本。
 */
public record InvChangeRow(
        /** INIT/PURCHASE_IN/PURCHASE_RETURN/SALE_OUT/SALE_RETURN/CHECK_SURPLUS/CHECK_SHORTAGE/LOSS_OUT/OVERFLOW_IN */
        String recordType,
        long recordCount,
        BigDecimal changeQty,
        BigDecimal amount) {
}

package com.smms.rpt.dto;

import java.math.BigDecimal;

/**
 * 损耗报表行（FR-RPT-04⑤）：报损 + 盘亏汇总（与销售退货分开统计）。
 * bizType：LOSS 报损 / OVERFLOW 报溢 / CHECK 盘亏。
 */
public record LossRow(
        Long goodsId,
        String goodsName,
        String categoryName,
        String bizType,
        BigDecimal lossQty,
        BigDecimal lossAmount) {
}

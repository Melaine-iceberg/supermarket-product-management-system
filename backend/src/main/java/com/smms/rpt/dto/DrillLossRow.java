package com.smms.rpt.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 下钻损耗单据行（BR-RPT-06）：报损单 / 盘点差异行，
 * 数量与金额之和与损耗报表行一致（报损、盘亏分列）。
 */
public record DrillLossRow(
        Long id,
        String orderNo,
        /** LOSS 报损 / CHECK 盘亏 */
        String kind,
        Long goodsId,
        String goodsName,
        BigDecimal qty,
        BigDecimal costPrice,
        BigDecimal amount,
        String reason,
        String applyName,
        String auditorName,
        LocalDateTime bizTime) {
}

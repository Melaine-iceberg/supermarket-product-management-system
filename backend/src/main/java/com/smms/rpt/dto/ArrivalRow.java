package com.smms.rpt.dto;

import java.math.BigDecimal;

/**
 * 到货及时率行（FR-RPT-06④）：按期到货占比。
 */
public record ArrivalRow(
        Long supplierId,
        String supplierName,
        long receiptCount,
        long onTimeCount,
        /** 及时率 = 按期次数 / 总次数 × 100% */
        BigDecimal onTimeRate) {
}

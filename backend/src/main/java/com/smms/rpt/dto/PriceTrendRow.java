package com.smms.rpt.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购价格波动点（FR-RPT-06③）：同商品多次采购单价趋势。
 */
public record PriceTrendRow(
        Long orderId,
        String orderNo,
        Long supplierId,
        String supplierName,
        LocalDateTime orderTime,
        BigDecimal quantity,
        BigDecimal price) {
}

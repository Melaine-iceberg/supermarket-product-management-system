package com.smms.inv.dto;

/**
 * 库存预警汇总（FR-INV-05）：各预警类型数量，供预警页签与首页看板角标（FR-RPT-01）使用。
 */
public record AlertSummaryVO(
        long outCount,
        long warningCount,
        long overstockCount
) {

    /** 预警总数（FR-RPT-01 看板角标口径） */
    public long totalCount() {
        return outCount + warningCount + overstockCount;
    }
}

package com.smms.rpt.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 销售报表通用分组行（日报/月报/收款方式/小时/收银员/收银台/会员占比）：
 * 不同分组维度复用同一行结构，groupKey 为日期/月份/收款方式/小时/收银员名/收银台号/会员标记。
 */
public record SalesGroupRow(
        String groupKey,
        LocalDate bizDate,
        long orderCount,
        Long itemCount,
        BigDecimal salesAmount,
        BigDecimal discountAmount,
        BigDecimal netSalesAmount,
        BigDecimal costAmount,
        BigDecimal refundAmount,
        BigDecimal returnCostAmount,
        Long returnCount) {
}

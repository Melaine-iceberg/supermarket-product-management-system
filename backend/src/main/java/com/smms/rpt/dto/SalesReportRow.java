package com.smms.rpt.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 销售报表行（FR-RPT-02）：日报/月报/收款方式/小时/收银员/收银台/会员占比复用同一结构；
 * groupKey 为日期(yyyy-MM-dd)/月份(yyyy-MM)/收款方式/小时/收银员姓名/收银台号/MEMBER|WALKIN|TOTAL。
 * 净销售 = 净销售额 − 退货额（AC-16）；毛利率/客单价见 BR-RPT-01。
 */
public record SalesReportRow(
        String groupKey,
        LocalDate bizDate,
        long orderCount,
        Long itemCount,
        BigDecimal salesAmount,
        BigDecimal discountAmount,
        BigDecimal netSalesAmount,
        BigDecimal refundAmount,
        Long returnCount,
        /** 净销售 = 净销售额 − 退货额 */
        BigDecimal netAmount,
        BigDecimal costAmount,
        BigDecimal profitAmount,
        BigDecimal profitRate,
        BigDecimal avgOrderAmount,
        BigDecimal shareRate) {
}

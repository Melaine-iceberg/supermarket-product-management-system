package com.smms.sale.dto;

import java.time.LocalDate;

/**
 * 退货单查询条件（FR-SALE-10）：退货单号/原销售单号/会员/退款方式/退货原因/时间区间。
 */
public record ReturnQuery(
        String orderNo,
        String salesOrderNo,
        String memberKeyword,
        String refundType,
        String returnReason,
        LocalDate beginDate,
        LocalDate endDate,
        /** 经办人（BR-SALE-09：收银员仅见本人经手，服务端注入，不开放为请求参数） */
        String operatorName,
        int page,
        int size
) {
}
